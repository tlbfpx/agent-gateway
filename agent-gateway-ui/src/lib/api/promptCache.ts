/**
 * promptCache.ts — 提示缓存（gateway.llm.prompt-cache）配置与指标
 *
 * 后端契约（并行开发中，后端保证按此交付）：
 *  - 配置项 gateway.llm.prompt-cache: { enabled: boolean, ttl: 10m, maxEntries: 1000 }
 *    暂无确定的读写 API；此处只尝试只读 GET /admin/config/gateway.llm.prompt-cache，
 *    不可达时返回 null（调用方降级为占位展示，不硬造写入接口）。
 *  - 指标计数器 prompt_cache_hit_total / prompt_cache_miss_total，
 *    通过 GET /actuator/metrics/{name}（Actuator 标准形状）读取。
 *  - chat done 事件 meta.cacheHit: boolean（见 lib/api/chat.ts 的 ChatMeta）。
 */
import { http, getApiKey, getTenant } from '../request';

export interface PromptCacheConfig {
  enabled: boolean;
  /** 如 '10m' */
  ttl: string;
  maxEntries: number;
}

export interface PromptCacheRate {
  hit: number;
  miss: number;
  /** hit/(hit+miss)；total=0 时为 null（界面显示 —） */
  rate: number | null;
}

/** 只读尝试读取配置；接口未提供时返回 null（不抛错） */
export async function getPromptCacheConfig(): Promise<PromptCacheConfig | null> {
  try {
    return await http.get<PromptCacheConfig>('/admin/config/gateway.llm.prompt-cache');
  } catch {
    return null;
  }
}

/** Actuator /actuator/metrics/{name} 返回形状（只取需要的字段） */
interface ActuatorMetric {
  name?: string;
  measurements?: Array<{ statistic?: string; value?: number }>;
}

async function fetchCounter(name: string): Promise<number | null> {
  try {
    const headers: Record<string, string> = {};
    const apiKey = getApiKey();
    const tenant = getTenant();
    if (apiKey) headers['X-API-Key'] = apiKey;
    if (tenant) headers['X-Tenant-Id'] = tenant;
    const res = await fetch(`/actuator/metrics/${name}`, { headers });
    if (!res.ok) return null;
    const j = (await res.json()) as ActuatorMetric;
    const ms = j.measurements ?? [];
    const m = ms.find((x) => x.statistic === 'COUNT') ?? ms[0];
    return typeof m?.value === 'number' ? m.value : null;
  } catch {
    return null;
  }
}

/** 拉取命中/未命中计数并计算命中率；任一计数不可得时返回 null（界面显示 —） */
export async function getPromptCacheRate(): Promise<PromptCacheRate | null> {
  const [hit, miss] = await Promise.all([
    fetchCounter('prompt_cache_hit_total'),
    fetchCounter('prompt_cache_miss_total'),
  ]);
  if (hit === null || miss === null) return null;
  const total = hit + miss;
  return { hit, miss, rate: total > 0 ? hit / total : null };
}

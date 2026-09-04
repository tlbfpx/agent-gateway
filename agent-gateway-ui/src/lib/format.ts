/**
 * format.ts — 通用格式化工具
 */

/** 相对时间：14:30 / 昨天 / 3 天前 */
export function relTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时前`;
  const d = Math.floor(h / 24);
  if (d === 1) return '昨天';
  if (d < 7) return `${d} 天前`;
  return new Date(iso).toLocaleDateString('zh-CN');
}

/** HH:mm 简式时间 */
export function shortTime(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** API Key 掩码：保留前缀 + 4 位 */
export function maskKey(v: string, prefix = 'pk_live_'): string {
  if (!v) return '';
  if (v.length <= prefix.length + 4) return v;
  const tail = v.slice(-4);
  return `${prefix}••••${tail}`;
}

/** 大数字千分位 */
export function formatNum(n: number): string {
  return n.toLocaleString('zh-CN');
}
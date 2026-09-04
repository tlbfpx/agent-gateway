/**
 * useResourceList — 统一封装「拉列表 + loading + error + reload + isEmpty」模板。
 *
 * 5 个 List 页面（Models / ApiKeys / Roles / Webhooks / Agents）原本各自粘贴
 *   const [data, setData]         = useState<T[]>([]);
 *   const [loading, setLoading]   = useState(false);
 *   const [error, setError]       = useState<string>('');
 *   const load = async () => { setLoading(true); try { setData(await fetcher()); }
 *                              catch (e) { setError(e?.message ?? '...'); }
 *                              finally { setLoading(false); } };
 *   useEffect(() => { load(); }, []);
 *
 * 此 hook 把上述模板收敛为单行调用，并额外保证：
 *   1) deps 数组变化触发自动重载
 *   2) 竞态安全（旧请求晚返回不会覆盖新 data）
 *   3) 默认错误走 notifyError：进**常驻**通知中心 + toast（Round 10 B-2）；
 *      自定义 onError 时跳过
 *   4) isEmpty 支持自定义判定（兼容后端用 { __empty: true } 占位的场景）
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { notifyError } from '../lib/request';

export interface ResourceListOptions<T, R = T[]> {
  /** 拉数据的副作用函数；throw 即视为错误。默认约定返回 `Promise<T[]>`；如返回包装对象（如 Paged）请同时给 `mapResult` */
  fetcher: () => Promise<R>;
  /** 把 fetcher 返回值（R）映射为 `T[]`。常见场景：R 是 `{ items, total, page, ... }` 包装 → T[] = items */
  mapResult?: (raw: R) => T[];
  /** 依赖数组，任意一项变化触发自动重载；不传 = 只在挂载时拉一次 */
  deps?: ReadonlyArray<unknown>;
  /** 默认错误提示文案（fallback 当 fetcher 没附 message 时）；同时作为通知中心的 context */
  errorMessage?: string;
  /** 自定义错误回调；不传则走 notifyError（常驻通知中心 + toast） */
  onError?: (err: Error) => void;
  /**
   * 空判定函数：返回 true 视为"有效数据条目"，false 视为占位
   * 默认行为：data.length === 0 视为空
   * 兼容后端：item.__empty === true 视为占位
   */
  emptyCheck?: (item: T) => boolean;
}

export interface ResourceListResult<T> {
  data: T[];
  loading: boolean;
  error: Error | null;
  isEmpty: boolean;
  /** 手动触发重新拉取（用于重试按钮） */
  reload: () => void;
}

export function useResourceList<T, R = T[]>(opts: ResourceListOptions<T, R>): ResourceListResult<T> {
  const { fetcher, mapResult, deps, errorMessage, onError, emptyCheck } = opts;

  const [data, setData] = useState<T[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);
  /**
   * nonce: 递增触发 reload()，不依赖外部 deps 修改
   * 初始 0 → 挂载时拉一次（与 deps[0]=undefined 配合）
   */
  const [nonce, setNonce] = useState(0);

  // 缓存 onError / errorMessage，避免 fetcher 因函数引用变化而重建
  const onErrorRef = useRef<((err: Error) => void) | undefined>(onError);
  onErrorRef.current = onError;
  const errMsgRef = useRef<string | undefined>(errorMessage);
  errMsgRef.current = errorMessage;
  const mapResultRef = useRef<((raw: R) => T[]) | undefined>(mapResult);
  mapResultRef.current = mapResult;

  const reload = useCallback(() => {
    setNonce((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetcher()
      .then((raw) => {
        if (cancelled) return;
        const mapped = mapResultRef.current ? mapResultRef.current(raw as R) : (raw as unknown as T[]);
        setData(mapped);
        setError(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const err = e instanceof Error ? e : new Error(String(e));
        setData([]);
        setError(err);
        const cb = onErrorRef.current;
        if (cb) {
          cb(err);
        } else {
          // Round 10 B-2：错误进常驻通知中心（+ toast 兜底），
          // 不再依赖 3 秒即逝的 message.error —— 用户切走 tab 也能事后回看。
          // errorMessage 作为 context，去重 key 因此按页面隔离。
          notifyError(err, errMsgRef.current ?? '加载');
        }
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...(deps ?? []), nonce]);

  const isEmpty = useMemoEmpty(data, emptyCheck);

  return { data, loading, error, isEmpty, reload };
}

/**
 * 默认空判定：data 长度为 0 即为空。
 * 自定义 emptyCheck：当作**过滤器**，对每条 item 调用，返回 false 表示「占位」（过滤掉）。
 *   例如 `emptyCheck: i => !i.__empty` 表示忽略带占位标记的项；
 *   过滤后若剩余 0 条 → 视为空；否则非空。
 *   兼容性：兼容后端用 `{__empty: true}` 占位的场景。
 */
function useMemoEmpty<T>(data: T[], emptyCheck?: (item: T) => boolean): boolean {
  if (!emptyCheck) return data.length === 0;
  for (const item of data) {
    if (emptyCheck(item)) return false; // 找到一条「保留」即非空
  }
  return true;
}

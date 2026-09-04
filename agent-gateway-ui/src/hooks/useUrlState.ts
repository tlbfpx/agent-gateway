/**
 * useUrlState.ts — URL 查询参数双向绑定 hook（Round 11 §ui-b5）
 *
 * 设计目标：
 *   - 把组件状态同步到 URL ?key=value,刷新/分享/后退都保留
 *   - 支持 string / number / boolean / enum 等基础类型
 *   - 与 react-router-dom v6 useSearchParams 配合
 *
 * 用法：
 *   const [tenant, setTenant] = useUrlState('tenant', 'au');
 *   const [range, setRange] = useUrlState('range', '24h');
 *
 * 行为：
 *   - 初始化从 URL 读取(若存在);否则用 defaultValue
 *   - setState 同时更新 URL;空值自动从 URL 移除
 *   - 监听 popstate(passive),URL 变化时同步回 state
 */

import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

type Setter<T> = (value: T | ((prev: T) => T)) => void;

function parseString(raw: string | null, fallback: string): string {
  return raw === null ? fallback : raw;
}

function parseNumber(raw: string | null, fallback: number): number {
  if (raw === null) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) ? n : fallback;
}

function parseBoolean(raw: string | null, fallback: boolean): boolean {
  if (raw === null) return fallback;
  return raw === 'true' || raw === '1';
}

export function useUrlState(key: string, defaultValue: string): [string, Setter<string>];
export function useUrlState(key: string, defaultValue: number): [number, Setter<number>];
export function useUrlState(key: string, defaultValue: boolean): [boolean, Setter<boolean>];
export function useUrlState(
  key: string,
  defaultValue: string | number | boolean,
): [string | number | boolean, Setter<string | number | boolean>] {
  const [searchParams, setSearchParams] = useSearchParams();

  const readFromUrl = useCallback(() => {
    const raw = searchParams.get(key);
    if (raw === null) return defaultValue;
    if (typeof defaultValue === 'number') return parseNumber(raw, defaultValue);
    if (typeof defaultValue === 'boolean') return parseBoolean(raw, defaultValue);
    return parseString(raw, defaultValue);
    // searchParams 来自 useSearchParams 的引用,会随 URL 变化而变化
  }, [searchParams, key, defaultValue]);

  const [value, setValue] = useState<string | number | boolean>(readFromUrl);

  // URL 外部变化(浏览器后退/前进) → 同步回 state
  useEffect(() => {
    const next = readFromUrl();
    setValue((prev) => (prev === next ? prev : next));
  }, [readFromUrl]);

  const setter: Setter<string | number | boolean> = useCallback((next) => {
    setValue((prev) => {
      const resolved =
        typeof next === 'function'
          ? (next as (p: typeof prev) => typeof prev)(prev)
          : next;
      // 写 URL(空值 / 默认值 → 删除 key;非空 → 序列化)
      setSearchParams(
        (current) => {
          const params = new URLSearchParams(current);
          if (resolved === '' || resolved === defaultValue || resolved === null || resolved === undefined) {
            params.delete(key);
          } else {
            params.set(key, String(resolved));
          }
          return params;
        },
        { replace: true },
      );
      return resolved;
    });
  }, [key, defaultValue, setSearchParams]);

  return [value, setter];
}

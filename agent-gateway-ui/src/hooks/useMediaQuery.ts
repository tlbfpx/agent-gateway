/**
 * useMediaQuery — 响应式断点 hook
 * - 通过 matchMedia 监听窗口宽度变化
 * - 返回 boolean：当前是否满足 query
 */
import { useEffect, useState } from 'react';

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    const mq = window.matchMedia(query);
    const onChange = () => setMatches(mq.matches);
    mq.addEventListener?.('change', onChange);
    setMatches(mq.matches);
    return () => mq.removeEventListener?.('change', onChange);
  }, [query]);

  return matches;
}

/** 移动端断点 < 768px */
export function useIsMobile(): boolean {
  return useMediaQuery('(max-width: 767px)');
}

/** 平板断点 768-1024px */
export function useIsTablet(): boolean {
  return useMediaQuery('(min-width: 768px) and (max-width: 1023px)');
}
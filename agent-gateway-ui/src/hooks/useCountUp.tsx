/**
 * useCountUp — 数字滚动动画
 *
 * 从 0（或上一值）缓动到目标值，用于 StatCard 数值的"活感"。
 * requestAnimationFrame + easeOutCubic；卸载自动取消。
 * 目标值为 0 或解析失败时直接显示，不做无意义动画。
 */
import { useEffect, useRef, useState } from 'react';

export function useCountUp(target: number, durationMs = 850): number {
  const [display, setDisplay] = useState(0);
  const fromRef = useRef(0);
  const rafRef = useRef<number>(0);

  useEffect(() => {
    const from = fromRef.current;
    if (target === from || !Number.isFinite(target)) {
      setDisplay(target);
      fromRef.current = target;
      return;
    }
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs);
      const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
      const v = from + (target - from) * eased;
      setDisplay(t === 1 ? target : v);
      if (t < 1) rafRef.current = requestAnimationFrame(tick);
      else fromRef.current = target;
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [target, durationMs]);

  return display;
}

/**
 * AnimatedNumber — 字符串数值中的整数部分 count-up。
 * 支持 "12,345" / "3.2%" / "210ms" 等混合格式：非数字字符原样保留。
 */
export function AnimatedNumber({ text, durationMs }: { text: string; durationMs?: number }) {
  // 拆出首个整数段（可含千分位逗号）做动画，前后缀原样
  const m = /^(\D*?)([\d,]+)(.*)$/.exec(text);
  if (!m) return <span>{text}</span>;

  const prefix = m[1];
  const intPart = m[2];
  const suffix = m[3];
  const target = Number(intPart.replace(/,/g, ''));
  if (!Number.isFinite(target) || target === 0) return <span>{text}</span>;

  return (
    <span>
      {prefix}
      <Counter target={target} durationMs={durationMs} />
      {suffix}
    </span>
  );
}

function Counter({ target, durationMs }: { target: number; durationMs?: number }) {
  const v = useCountUp(target, durationMs);
  return <span>{Math.round(v).toLocaleString('zh-CN')}</span>;
}

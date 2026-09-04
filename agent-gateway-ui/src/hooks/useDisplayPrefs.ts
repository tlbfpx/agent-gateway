/**
 * useDisplayPrefs — 主题与密度偏好
 *
 * - theme: 'light' | 'dark' | 'system'（system 通过 matchMedia 跟随系统）
 * - density: 'comfortable' | 'compact' | 'loose'
 *
 * 持久化：localStorage `agent-gateway.displayPrefs`
 * 落地：通过 data-theme / data-density 属性挂在 <html> 上，CSS 变量自动覆盖
 */
import { useEffect, useState, useCallback } from 'react';

export type Theme = 'light' | 'dark' | 'system';
export type Density = 'comfortable' | 'compact' | 'loose';

interface Prefs {
  theme: Theme;
  density: Density;
}

const KEY = 'agent-gateway.displayPrefs';

const DEFAULT: Prefs = { theme: 'light', density: 'comfortable' };

function read(): Prefs {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return DEFAULT;
    const parsed = JSON.parse(raw);
    return { theme: parsed.theme ?? DEFAULT.theme, density: parsed.density ?? DEFAULT.density };
  } catch {
    return DEFAULT;
  }
}

function write(p: Prefs) {
  try {
    localStorage.setItem(KEY, JSON.stringify(p));
  } catch {
    /* localStorage 不可用时静默 */
  }
}

function apply(p: Prefs) {
  const html = document.documentElement;
  const theme =
    p.theme === 'system'
      ? window.matchMedia?.('(prefers-color-scheme: dark)').matches
        ? 'dark'
        : 'light'
      : p.theme;
  html.setAttribute('data-theme', theme);
  html.setAttribute('data-density', p.density);
}

export function useDisplayPrefs() {
  const [prefs, setPrefs] = useState<Prefs>(DEFAULT);

  // 首次挂载：读 localStorage + 应用
  useEffect(() => {
    const p = read();
    setPrefs(p);
    apply(p);
  }, []);

  // 系统主题切换（仅在 theme=system 时响应）
  useEffect(() => {
    if (prefs.theme !== 'system') return;
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const onChange = () => apply(prefs);
    mq.addEventListener?.('change', onChange);
    return () => mq.removeEventListener?.('change', onChange);
  }, [prefs]);

  const setTheme = useCallback((theme: Theme) => {
    setPrefs((p) => {
      const next = { ...p, theme };
      write(next);
      apply(next);
      return next;
    });
  }, []);

  const setDensity = useCallback((density: Density) => {
    setPrefs((p) => {
      const next = { ...p, density };
      write(next);
      apply(next);
      return next;
    });
  }, []);

  return { prefs, setTheme, setDensity };
}

/** 单纯应用一次（适合在 AppShell 入口调用，确保 mount 时生效） */
export function applyDisplayPrefsOnce() {
  apply(read());
}
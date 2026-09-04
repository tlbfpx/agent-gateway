/**
 * useCommandPalette — 全局 ⌘K 命令面板开关状态
 * - 跨组件共享打开状态（zustand 风格的轻量自实现）
 * - 监听 ⌘K / Ctrl+K 全局快捷键
 * - 注意：避免在 input/textarea 中误触发
 */
import { useEffect, useState, useCallback } from 'react';

type Listener = (open: boolean) => void;

let _open = false;
const _listeners = new Set<Listener>();

function setOpen(v: boolean) {
  if (_open === v) return;
  _open = v;
  _listeners.forEach((fn) => fn(v));
}

export function openPalette() {
  setOpen(true);
}

export function closePalette() {
  setOpen(false);
}

export function togglePalette() {
  setOpen(!_open);
}

export function usePaletteOpen(): boolean {
  const [open, setLocal] = useState(_open);
  useEffect(() => {
    const fn: Listener = (v) => setLocal(v);
    _listeners.add(fn);
    return () => {
      _listeners.delete(fn);
    };
  }, []);
  return open;
}

/** 在组件挂载时自动绑定 ⌘K / Ctrl+K 监听 */
export function useGlobalPaletteShortcut() {
  const onKey = useCallback((e: KeyboardEvent) => {
    const isK = e.key === 'k' || e.key === 'K';
    if (!isK) return;
    const cmd = e.metaKey || e.ctrlKey;
    if (!cmd) return;
    // 在可编辑元素中仍允许，但避免 shift 等组合
    if (e.shiftKey || e.altKey) return;
    e.preventDefault();
    togglePalette();
  }, []);
  useEffect(() => {
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onKey]);
}

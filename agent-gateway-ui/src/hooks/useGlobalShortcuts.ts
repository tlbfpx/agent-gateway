/**
 * useGlobalShortcuts — ⌘+/ 帮助面板 + ⌘+1-9 跳菜单
 *
 * 与现有 ⌘K（命令面板）共同构成完整快捷键体系
 */
import { useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { openPalette, closePalette } from './useCommandPalette';

let _helpOpen = false;
const _listeners = new Set<(v: boolean) => void>();

export function openHelp() {
  if (_helpOpen) return;
  _helpOpen = true;
  _listeners.forEach((fn) => fn(true));
}

export function closeHelp() {
  if (!_helpOpen) return;
  _helpOpen = false;
  _listeners.forEach((fn) => fn(false));
}

export function useHelpOpen(): boolean {
  const [open, setOpen] = useStateInner();
  useEffect(() => {
    const fn = (v: boolean) => setOpen(v);
    _listeners.add(fn);
    return () => {
      _listeners.delete(fn);
    };
  }, [setOpen]);
  return open;
}

import { useState } from 'react';
function useStateInner() {
  return useState(_helpOpen);
}

/** 跳菜单路由（与 Sidebar 顺序一致） */
const QUICK_NAV: { key: string; path: string }[] = [
  { key: '1', path: '/dashboard' },
  { key: '2', path: '/models' },
  { key: '3', path: '/api-keys' },
  { key: '4', path: '/agents' },
  { key: '5', path: '/cost' },
  { key: '6', path: '/alerts' },
  { key: '7', path: '/audit' },
  { key: '8', path: '/chat' },
  { key: '9', path: '/help' },
];

/** 在 AppShell mount 时调用一次 */
export function useGlobalShortcuts() {
  const navigate = useNavigate();

  const onKey = useCallback(
    (e: KeyboardEvent) => {
      const cmd = e.metaKey || e.ctrlKey;

      // ⌘+/ 或 ⌘?：帮助面板
      if (cmd && (e.key === '/' || e.key === '?' || e.key === ',' || e.key === '，')) {
        if (e.shiftKey || e.key === '/') {
          e.preventDefault();
          if (_helpOpen) closeHelp();
          else openHelp();
        }
      }

      // ⌘+1-9：跳菜单（仅当不在输入框时）
      if (cmd && /^[1-9]$/.test(e.key)) {
        const target = e.target as HTMLElement;
        const tag = target.tagName?.toLowerCase();
        if (tag === 'input' || tag === 'textarea' || target.isContentEditable) return;
        e.preventDefault();
        const nav = QUICK_NAV.find((n) => n.key === e.key);
        if (nav) navigate(nav.path);
      }

      // Esc：关闭当前打开的弹窗（help / palette）
      if (e.key === 'Escape') {
        if (_helpOpen) closeHelp();
        else closePalette();
      }
    },
    [navigate],
  );

  useEffect(() => {
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onKey]);
}
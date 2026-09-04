/**
 * useEventBus — 全局事件总线
 *
 * 用途：跨页面数据同步
 *   - 用户在 /api-keys 撤销一个 Key → publish('apikeys:changed')
 *   - /dashboard 监听此事件 → 重新拉数据
 *   - Sidebar 状态徽章也监听 → 自动更新计数
 *
 * 设计：
 *   - 单例 event bus（不引入第三方库）
 *   - 类型安全：EventMap 声明所有事件 + payload 类型
 *   - 自动 cleanup：useEffect 返回时退订
 *   - 与 window 'storage' 事件打通：多 tab 数据也能同步
 */
import { useEffect, useCallback } from 'react';

export interface EventMap {
  // 数据变更
  'models:changed': undefined;
  'apikeys:changed': undefined;
  'agents:changed': undefined;
  'webhooks:changed': undefined;
  'policies:changed': undefined;
  'alerts:changed': undefined;
  'notifications:changed': undefined;
  // 用户/会话
  'tenant:switched': { tenant: string };
  'role:switched': { role: string };
  // 主题/偏好
  'theme:changed': { theme: 'light' | 'dark' };
  // 强制刷新所有页面（紧急情况下用）
  'app:refresh': undefined;
}

type EventName = keyof EventMap;
type Listener<K extends EventName> = (payload: EventMap[K]) => void;

const _listeners: Map<EventName, Set<Listener<EventName>>> = new Map();

function _emit<K extends EventName>(name: K, payload: EventMap[K]) {
  const set = _listeners.get(name);
  if (!set) return;
  set.forEach((fn) => {
    try {
      (fn as Listener<K>)(payload);
    } catch (e) {
      // eslint-disable-next-line no-console
      console.error(`[EventBus] listener for ${String(name)} threw:`, e);
    }
  });
}

/** 触发事件 */
export function emit<K extends EventName>(name: K, payload: EventMap[K]) {
  _emit(name, payload);
}

/** 订阅事件（返回 unsubscribe 函数） */
export function on<K extends EventName>(name: K, listener: Listener<K>): () => void {
  let set = _listeners.get(name);
  if (!set) {
    set = new Set();
    _listeners.set(name, set);
  }
  set.add(listener as Listener<EventName>);
  return () => {
    set?.delete(listener as Listener<EventName>);
  };
}

/** React Hook：订阅事件 */
export function useEvent<K extends EventName>(
  name: K,
  handler: Listener<K>,
  deps: ReadonlyArray<unknown> = [],
) {
  useEffect(() => {
    return on(name, handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** 简单触发器 hook：返回 emit 函数 */
export function useEmit<K extends EventName>() {
  return useCallback(<P extends EventMap[K]>(name: K, payload: P) => emit(name, payload), []);
}

/** 与多 tab 同步：监听 localStorage 跨 tab 变更 */
const SYNC_KEY = 'agent-gateway.event-bus';

export function broadcastAcrossTabs<K extends EventName>(name: K, payload: EventMap[K]) {
  emit(name, payload);
  try {
    localStorage.setItem(SYNC_KEY, JSON.stringify({ name, payload, t: Date.now() }));
  } catch {
    /* silent */
  }
}

let _storageBound = false;

/** 启动跨 tab 事件同步（AppShell mount 时调用一次） */
export function enableCrossTabSync() {
  if (_storageBound || typeof window === 'undefined') return;
  _storageBound = true;
  window.addEventListener('storage', (e) => {
    if (e.key !== SYNC_KEY || !e.newValue) return;
    try {
      const { name, payload } = JSON.parse(e.newValue);
      _emit(name as EventName, payload);
    } catch {
      /* silent */
    }
  });
}

/** 调试：列出所有事件名（开发用） */
export function listEvents(): EventName[] {
  return Array.from(_listeners.keys()) as EventName[];
}
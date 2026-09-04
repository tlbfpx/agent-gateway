import '@testing-library/jest-dom/vitest';

/**
 * jsdom 25 / 内置 undici 对 fetch Request 的 signal 严格校验非常严格：
 * 任何 AbortController.signal 跨包/跨 realm 传入 new Request 都会被拒
 * （"Expected signal to be an instance of AbortSignal"）。
 *
 * 这里给 fetch 包一层，必要时剥离 signal 后再走原 fetch。
 */
const setupFetch = window.fetch.bind(window);
window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
  // 1) init.signal 替换：无论来源，统一用 jsdom 本地 controller 重建
  let nextInit = init;
  if (init && (init as { signal?: unknown }).signal) {
    try {
      const ac = new AbortController();
      const s = (init as { signal: { aborted?: boolean; addEventListener?: (t: string, l: () => void) => void } }).signal;
      if (s.aborted) ac.abort();
      if (typeof s.addEventListener === 'function') {
        s.addEventListener('abort', () => ac.abort());
      }
      nextInit = { ...init, signal: ac.signal };
    } catch {
      // 信号有问题则直接剥掉，让上层走默认超时
      const { signal: _omit, ...rest } = init as RequestInit & { signal?: unknown };
      nextInit = rest as RequestInit;
    }
  }
  // 2) input 是 Request：重建一个不传 signal 的 Request（有些内部代码会再 new Request）
  if (typeof input === 'object' && input !== null && !(input instanceof URL) && 'signal' in (input as object)) {
    try {
      const r = input as unknown as Request;
      input = new Request(r.url, {
        method: r.method,
        headers: r.headers,
        body: r.bodyUsed ? undefined : r.body,
        signal: undefined as unknown as AbortSignal,
      } as RequestInit);
    } catch {
      /* fall through to original */
    }
  }
  return (setupFetch as any)(input, nextInit);
};

// jsdom 25+ 不再默认暴露 localStorage 到 globalThis，这里手动挂一份。
// 用最小 Map 实现满足 request.ts 的使用（getItem/setItem/removeItem/clear/key）。
class MemoryStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  key(i: number): string | null {
    return Array.from(this.store.keys())[i] ?? null;
  }
  getItem(k: string): string | null {
    return this.store.get(k) ?? null;
  }
  setItem(k: string, v: string): void {
    this.store.set(k, String(v));
  }
  removeItem(k: string): void {
    this.store.delete(k);
  }
  clear(): void {
    this.store.clear();
  }
}
const mem = new MemoryStorage();
Object.defineProperty(globalThis, 'localStorage', { value: mem, writable: false });
// 也暴露到 window 以防 antd 内部走 window.localStorage
try {
  Object.defineProperty(window, 'localStorage', { value: mem, configurable: true });
} catch {
  /* ignore */
}

// Polyfill for antd / matchMedia
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// jsdom 不实现 getComputedStyle 的第二参数（pseudoElt）；antd 内部会调。
// 给一个最小回退，避免 "Not implemented" 警告打断断言。
const origGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (elt: Element, _pseudoElt?: string | null) => origGetComputedStyle(elt);

// IntersectionObserver stub (antd uses it)
class IntersectionObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
Object.defineProperty(window, 'IntersectionObserver', {
  writable: true,
  value: IntersectionObserverStub,
});

// jsdom 不实现 Element.scrollIntoView（Chat 页面 useEffect 调用它）
(Element.prototype as unknown as { scrollIntoView?: () => void }).scrollIntoView = function () {
  /* no-op */
};

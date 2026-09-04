/**
 * BundleDownloader — Round 10 OpenSpec GW-OAB-007/008/010 测试
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

// antd message 在 setup.ts 里有 polyfill；这里 mock 掉以观察调用
vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn(),
      loading: vi.fn(),
    },
  };
});

import { message } from 'antd';
import { BundleDownloader } from '../src/components/openapi/BundleDownloader';
import * as openapiApi from '../src/lib/api/openapi';

describe('BundleDownloader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 保留 createObjectURL / revokeObjectURL / appendChild 等的最小可用实现
    if (!('createObjectURL' in URL)) {
      Object.defineProperty(URL, 'createObjectURL', { value: () => 'blob:test', configurable: true });
      Object.defineProperty(URL, 'revokeObjectURL', { value: () => {}, configurable: true });
    }
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders three buttons (Python / TypeScript / Go)', () => {
    render(<BundleDownloader />);
    expect(screen.getByTestId('bundle-download-python')).toBeInTheDocument();
    expect(screen.getByTestId('bundle-download-typescript')).toBeInTheDocument();
    expect(screen.getByTestId('bundle-download-go')).toBeInTheDocument();
    // 文本内容
    expect(screen.getByText('Python SDK')).toBeInTheDocument();
    expect(screen.getByText('TypeScript SDK')).toBeInTheDocument();
    expect(screen.getByText('Go SDK')).toBeInTheDocument();
  });

  it('clicking python button triggers downloadOpenApiBundle with python', async () => {
    const spy = vi.spyOn(openapiApi, 'downloadOpenApiBundle').mockResolvedValue(undefined);

    render(<BundleDownloader />);
    const btn = screen.getByTestId('bundle-download-python');
    fireEvent.click(btn);

    await waitFor(() => {
      expect(spy).toHaveBeenCalledWith('python');
    });
    expect(spy).toHaveBeenCalledTimes(1);
    expect(message.success).toHaveBeenCalled();
  });

  it('clicking typescript button triggers download with typescript', async () => {
    const spy = vi.spyOn(openapiApi, 'downloadOpenApiBundle').mockResolvedValue(undefined);

    render(<BundleDownloader />);
    fireEvent.click(screen.getByTestId('bundle-download-typescript'));

    await waitFor(() => {
      expect(spy).toHaveBeenCalledWith('typescript');
    });
  });

  it('fetch 503 shows error toast and continues', async () => {
    vi.spyOn(openapiApi, 'downloadOpenApiBundle').mockRejectedValue(
      new Error('bundle go HTTP 503'),
    );

    render(<BundleDownloader />);
    fireEvent.click(screen.getByTestId('bundle-download-go'));

    await waitFor(() => {
      expect(message.error).toHaveBeenCalled();
    });
    const errArgs = (message.error as unknown as { mock: { calls: unknown[][] } }).mock.calls;
    // BundleDownloader 把 503 显式翻译为「暂未发布」运营文案
    expect(String(errArgs[0][0])).toContain('503');
    expect(String(errArgs[0][0])).toContain('go');
  });

  it('getOpenApiBundleUrl returns query-encoded lang', () => {
    expect(openapiApi.getOpenApiBundleUrl('python')).toBe('/v1/openapi/bundle?lang=python');
    expect(openapiApi.getOpenApiBundleUrl('go')).toBe('/v1/openapi/bundle?lang=go');
  });

  it('isBundleLang accepts only the 3 supported langs', () => {
    expect(openapiApi.isBundleLang('python')).toBe(true);
    expect(openapiApi.isBundleLang('typescript')).toBe(true);
    expect(openapiApi.isBundleLang('go')).toBe(true);
    expect(openapiApi.isBundleLang('rust')).toBe(false);
    expect(openapiApi.isBundleLang('')).toBe(false);
  });

  it('getOpenApiBundleBlob passes X-API-Key header', async () => {
    const original = localStorage.getItem.bind(localStorage);
    localStorage.getItem = (k: string) => (k === 'agent-gateway.apiKey' ? 'sk-test-key-7777' : original(k));

    const fakeBlob = new Blob(['PK'], { type: 'application/octet-stream' });
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(fakeBlob, { status: 200, headers: { 'Content-Type': 'application/zip' } }),
    );

    const blob = await openapiApi.getOpenApiBundleBlob('python');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/v1/openapi/bundle?lang=python');
    expect((init as RequestInit).headers).toMatchObject({ 'X-API-Key': 'sk-test-key-7777' });
    expect(blob.size).toBeGreaterThan(0);

    localStorage.getItem = original;
  });
});

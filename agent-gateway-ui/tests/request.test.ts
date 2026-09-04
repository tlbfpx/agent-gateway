import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { http, request, ApiError, getApiKey, setApiKey, getTenant, setTenant, clearAuth, getAdminToken, setAdminToken } from '../src/lib/request';

describe('lib/request', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('setApiKey / getApiKey roundtrip', () => {
    setApiKey('pk_test_123');
    expect(getApiKey()).toBe('pk_test_123');
  });

  it('setTenant / getTenant roundtrip', () => {
    setTenant('tenant-b');
    expect(getTenant()).toBe('tenant-b');
  });

  it('clearAuth removes both（清空后回落演示 key——开箱即用语义）', () => {
    setApiKey('k');
    setTenant('t');
    clearAuth();
    // 清除后 getApiKey 回落到演示 key（首次访问预填），不再返回空
    expect(getApiKey()).toBe('sk-demo-primary-0001');
    expect(getTenant()).toBe('primary');
  });

  it('request attaches X-API-Key + X-Tenant-Id + Content-Type', async () => {
    setApiKey('pk_test');
    setTenant('tenant-z');
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const data = await request<{ ok: boolean }>('/admin/keys');
    expect(data.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/v1/admin/keys');
    expect(init.headers['X-API-Key']).toBe('pk_test');
    expect(init.headers['X-Tenant-Id']).toBe('tenant-z');
    expect(init.headers['Content-Type']).toBe('application/json');
  });

  it('throws ApiError with status + message on non-2xx', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: 'forbidden' }), { status: 403 }),
      ),
    );
    await expect(http.get('/admin/keys')).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
      message: 'forbidden',
    });
  });

  it('returns undefined body on 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const r = await http.delete('/admin/keys/1');
    expect(r).toBeUndefined();
  });

  it('serializes body as JSON on POST', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('{}', { status: 200 })),
    );
    await http.post('/admin/keys', { name: 'foo', n: 1 });
    const [, init] = (fetch as any).mock.calls[0];
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"name":"foo","n":1}');
  });

  it('clears auth on 401', async () => {
    setApiKey('will-be-cleared');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: 'unauth' }), { status: 401 }),
      ),
    );
    await expect(http.get('/admin/keys')).rejects.toBeInstanceOf(ApiError);
    // 401 清除后回落演示 key（预填语义）
    expect(getApiKey()).toBe('sk-demo-primary-0001');
  });

  it('setAdminToken / getAdminToken roundtrip；空=未配置', () => {
    expect(getAdminToken()).toBe('');
    setAdminToken('adm-secret');
    expect(getAdminToken()).toBe('adm-secret');
    setAdminToken('');
    expect(getAdminToken()).toBe('');
  });

  it('adminToken 配置后仅对 /admin 路径附带 X-Admin-Token', async () => {
    setAdminToken('adm-secret');
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })),
    );
    vi.stubGlobal('fetch', fetchMock);

    await request('/admin/api-keys');
    await request('/chat/echo-agent');
    await request('/admin/models');

    const [u1, i1] = fetchMock.mock.calls[0];
    expect(u1).toBe('/v1/admin/api-keys');
    expect(i1.headers['X-Admin-Token']).toBe('adm-secret');
    const [, i2] = fetchMock.mock.calls[1];
    expect(i2.headers['X-Admin-Token']).toBeUndefined();
    const [, i3] = fetchMock.mock.calls[2];
    expect(i3.headers['X-Admin-Token']).toBe('adm-secret');
  });

  it('adminToken 未配置时不发送 X-Admin-Token 头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await request('/admin/api-keys');

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['X-Admin-Token']).toBeUndefined();
  });

  it('clearAuth 不清除 adminToken（独立管理凭据）', () => {
    setAdminToken('adm-secret');
    setApiKey('k');
    clearAuth();
    expect(getAdminToken()).toBe('adm-secret');
  });

  it('serializes params to query string (single values + array)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);
    await http.get('/admin/agents', {
      params: { q: 'foo', page: 2, pageSize: 10, tags: ['a', 'b'], skip: undefined, empty: '' },
    });
    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe('/v1/admin/agents?q=foo&page=2&pageSize=10&tags=a&tags=b');
  });
});
/**
 * openapi.test.ts — OpenAPI 文档解析
 */
import { describe, it, expect } from 'vitest';
import { flattenEndpoints, groupByTag } from '../src/lib/api/openapi';
import type { OpenApiDoc } from '../src/lib/api/openapi';

const MOCK_DOC: OpenApiDoc = {
  openapi: '3.0.3',
  info: { title: 'Agent Gateway', version: '1.0.0' },
  paths: {
    '/v1/chat': {
      post: { summary: '发送消息', tags: ['Chat'] },
      get: { summary: '列出消息', tags: ['Chat'] },
    },
    '/v1/admin/models': {
      get: { summary: '列出模型', tags: ['Models'] },
      post: { summary: '创建模型', tags: ['Models'] },
    },
    '/v1/admin/api-keys': {
      get: { summary: '列出 Key', tags: ['Admin'] },
    },
    '/v1/internal/metrics': {
      get: { summary: '内部指标', tags: [] as unknown as string[] },
    },
  },
};

describe('flattenEndpoints', () => {
  it('把 paths 摊平成端点数组', () => {
    const eps = flattenEndpoints(MOCK_DOC);
    expect(eps.length).toBe(6); // 2 + 2 + 1 + 1
  });

  it('method 大写', () => {
    const eps = flattenEndpoints(MOCK_DOC);
    expect(eps.every((e) => e.method === e.method.toUpperCase())).toBe(true);
  });

  it('未分类 tag 处理', () => {
    const eps = flattenEndpoints(MOCK_DOC);
    const internal = eps.find((e) => e.path === '/v1/internal/metrics');
    expect(internal).toBeDefined();
    expect(internal!.tags).toEqual(['未分类']);
  });
});

describe('groupByTag', () => {
  it('按 tag 分组', () => {
    const eps = flattenEndpoints(MOCK_DOC);
    const m = groupByTag(eps);
    expect(m.size).toBe(4); // Chat / Models / Admin / 未分类
    expect(m.get('Chat')?.length).toBe(2);
    expect(m.get('Models')?.length).toBe(2);
    expect(m.get('Admin')?.length).toBe(1);
    expect(m.get('未分类')?.length).toBe(1);
  });

  it('空端点 → 空 Map', () => {
    const m = groupByTag([]);
    expect(m.size).toBe(0);
  });
});
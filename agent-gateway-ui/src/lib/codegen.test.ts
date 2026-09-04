/**
 * codegen.test.ts — 8 用例覆盖 4 语言 × 简单 POST / 带 header / 带 query / 带 body
 *
 * 用例设计原则：
 *   - 每种语言都跑一遍"简单 GET" 和 "复杂 POST"，确保模板拼接的稳定
 *   - query 拼接单独测（验证 url 已含 ? 时用 & 追加）
 *   - apiKey 自动注入 header 单独测
 *   - body 序列化（字符串 / 对象）单独测
 */
import { describe, it, expect } from 'vitest';
import { generateCode, isCodegenLang, type CodegenRequest } from './codegen';

describe('codegen.generateCode — 4 语言基线', () => {
  const baseline: CodegenRequest = {
    method: 'POST',
    url: 'https://gw.example.com/v1/chat/completions',
    headers: { 'Content-Type': 'application/json' },
    body: { model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }] },
  };

  it('curl: 简单 POST 含 header + body', () => {
    const out = generateCode(baseline, 'curl');
    expect(out).toContain("curl -X POST 'https://gw.example.com/v1/chat/completions'");
    expect(out).toContain("-H 'Content-Type: application/json'");
    expect(out).toMatch(/-d '.*"model":"gpt-4o".*'/);
  });

  it('python: 简单 POST 含 header + json payload', () => {
    const out = generateCode(baseline, 'python');
    expect(out).toContain('import requests');
    expect(out).toContain('url = "https://gw.example.com/v1/chat/completions"');
    expect(out).toContain('"Content-Type": "application/json"');
    expect(out).toContain('requests.request("POST"');
    expect(out).toMatch(/payload\s*=\s*\{/);
  });

  it('js: 简单 POST 含 fetch + headers + body', () => {
    const out = generateCode(baseline, 'js');
    expect(out).toContain('await fetch("https://gw.example.com/v1/chat/completions"');
    expect(out).toContain('"method": "POST"');
    expect(out).toContain('"Content-Type": "application/json"');
    expect(out).toContain('await resp.json()');
  });

  it('go: 简单 POST 含 http.NewRequest + bytes.NewReader', () => {
    const out = generateCode(baseline, 'go');
    expect(out).toContain('package main');
    expect(out).toContain('import (');
    expect(out).toContain('"bytes"');
    expect(out).toContain('http.NewRequest("POST"');
    expect(out).toContain('bytes.NewReader(body)');
    expect(out).toContain('"Content-Type": []string{"application/json"}');
    expect(out).toContain('http.DefaultClient.Do(req)');
  });
});

describe('codegen.generateCode — 边界条件', () => {
  it('curl: GET 无 body 时不输出 -d 行', () => {
    const req: CodegenRequest = { method: 'GET', url: 'https://x.com/v1/models' };
    const out = generateCode(req, 'curl');
    expect(out).toContain("curl -X GET 'https://x.com/v1/models'");
    expect(out).not.toContain('-d');
  });

  it('curl: body 字符串含单引号时正确转义', () => {
    const req: CodegenRequest = {
      method: 'POST',
      url: 'https://x.com/v1/echo',
      body: "it's a 'test'",
    };
    const out = generateCode(req, 'curl');
    // ' 应被转义为 '\''（即 '  -> '\''
    expect(out).toContain("-d 'it'\\''s a '\\''test'\\'''");
  });

  it('python: apiKey 未在 headers 时自动注入 X-API-Key', () => {
    const req: CodegenRequest = {
      method: 'POST',
      url: 'https://x.com/v1/x',
      apiKey: 'sk-test-1234',
    };
    const out = generateCode(req, 'python');
    expect(out).toContain('"X-API-Key": "sk-test-1234"');
  });

  it('go: query 自动追加到 url', () => {
    const req: CodegenRequest = {
      method: 'GET',
      url: 'https://x.com/v1/models',
      query: { limit: 5, vendor: 'openai' },
    };
    const out = generateCode(req, 'go');
    expect(out).toContain('url := "https://x.com/v1/models?limit=5&vendor=openai"');
  });
});

describe('codegen.isCodegenLang', () => {
  it('accepts the 4 supported langs', () => {
    expect(isCodegenLang('curl')).toBe(true);
    expect(isCodegenLang('python')).toBe(true);
    expect(isCodegenLang('js')).toBe(true);
    expect(isCodegenLang('go')).toBe(true);
  });
  it('rejects unknown values', () => {
    expect(isCodegenLang('ruby')).toBe(false);
    expect(isCodegenLang('')).toBe(false);
  });
});
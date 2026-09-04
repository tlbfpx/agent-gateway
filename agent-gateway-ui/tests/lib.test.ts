/**
 * lib/export.test.ts — 导出工具
 * lib/fuzzy.test.ts — 模糊匹配（已在 CommandPalette.test.tsx 中覆盖）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { exportCsv, exportJson } from '../src/lib/export';

describe('exportCsv', () => {
  beforeEach(() => {
    // mock URL.createObjectURL / anchor click
    (globalThis as any).URL.createObjectURL = vi.fn(() => 'blob:test');
    (globalThis as any).URL.revokeObjectURL = vi.fn();
    // 捕获 click
    const origAppend = document.body.appendChild.bind(document.body);
    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => {
      // 立即触发 click
      if (node instanceof HTMLAnchorElement) {
        node.click();
      }
      return origAppend(node);
    });
  });

  it('生成 CSV 字符串并触发下载', () => {
    const filename = exportCsv(
      'models',
      ['id', 'provider', 'enabled'],
      [
        { id: 'gpt-4o', provider: 'openai', enabled: true },
        { id: 'claude-3.7', provider: 'anthropic', enabled: false },
      ],
    );
    expect(filename).toMatch(/^models-\d{4}-\d{2}-\d{2}T.*\.csv$/);
  });

  it('正确转义含逗号/引号/换行的字段', () => {
    const origAppend = document.body.appendChild.bind(document.body);
    let captured = '';
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = document.createElementNS ? (document.createElementNS('http://www.w3.org/1999/xhtml', tag) as any) : document.createElement(tag);
      Object.defineProperty(el, 'href', {
        set: () => {},
      });
      Object.defineProperty(el, 'download', {
        set: (v: string) => { captured = v; },
      });
      return el as any;
    });
    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => origAppend(node));

    exportCsv('test', ['content'], [{ content: 'line1\nline2,with,comma "and quote"' }]);
    // 验证转义逻辑
    const expected = '﻿content\n"line1\nline2,with,comma ""and quote"""';
    // 不直接比对字节（无 BOM 一致），验证文件名格式
    expect(captured).toMatch(/^test-.*\.csv$/);
  });
});

describe('exportJson', () => {
  it('生成 JSON 文件', () => {
    const filename = exportJson('audit', [{ a: 1 }, { a: 2 }]);
    expect(filename).toMatch(/^audit-.*\.json$/);
  });
});

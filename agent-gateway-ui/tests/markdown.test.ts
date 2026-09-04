/**
 * markdown.test.ts — Markdown 解析 & 渲染
 */
import { describe, it, expect } from 'vitest';
import { parseBlocks, renderMarkdown, escapeHtml, blockToHtml } from '../src/lib/markdown';

describe('escapeHtml', () => {
  it('转义 < > & " \'', () => {
    expect(escapeHtml('<script>alert("x&y")</script>')).toBe(
      '&lt;script&gt;alert(&quot;x&amp;y&quot;)&lt;/script&gt;',
    );
    // 单引号转义为 &#39;
    expect(escapeHtml("it's")).toBe('it&#39;s');
  });

  it('空字符串', () => {
    expect(escapeHtml('')).toBe('');
  });
});

describe('parseBlocks', () => {
  it('段落', () => {
    const blocks = parseBlocks('hello world\nfoo bar');
    expect(blocks).toHaveLength(1);
    expect(blocks[0].kind).toBe('p');
    expect(blocks[0].text).toBe('hello world\nfoo bar');
  });

  it('标题 h1-h6', () => {
    expect(parseBlocks('# H1')[0].kind).toBe('h1');
    expect(parseBlocks('## H2')[0].kind).toBe('h2');
    expect(parseBlocks('### H3')[0].kind).toBe('h3');
    expect(parseBlocks('#### H4')[0].kind).toBe('h4');
    expect(parseBlocks('##### H5')[0].kind).toBe('h5');
    expect(parseBlocks('###### H6')[0].kind).toBe('h6');
  });

  it('代码块 + 语言标识', () => {
    const blocks = parseBlocks('```python\nprint("hi")\n```');
    expect(blocks).toHaveLength(1);
    expect(blocks[0].kind).toBe('pre');
    expect(blocks[0].lang).toBe('python');
    expect(blocks[0].text).toBe('print("hi")');
  });

  it('代码块不带语言', () => {
    const blocks = parseBlocks('```\nplain\n```');
    expect(blocks[0].lang).toBe('');
    expect(blocks[0].text).toBe('plain');
  });

  it('无序列表', () => {
    const blocks = parseBlocks('- a\n- b\n- c');
    expect(blocks[0].kind).toBe('ul');
    expect(blocks[0].items).toEqual(['a', 'b', 'c']);
  });

  it('有序列表', () => {
    const blocks = parseBlocks('1. a\n2. b');
    expect(blocks[0].kind).toBe('ol');
    expect(blocks[0].items).toEqual(['a', 'b']);
  });

  it('引用', () => {
    const blocks = parseBlocks('> first\n> second');
    expect(blocks[0].kind).toBe('blockquote');
    expect(blocks[0].text).toBe('first\nsecond');
  });

  it('分隔线', () => {
    expect(parseBlocks('---')[0].kind).toBe('hr');
  });

  it('表格', () => {
    const md = '| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |';
    const blocks = parseBlocks(md);
    expect(blocks[0].kind).toBe('table');
    expect(blocks[0].rows).toEqual([
      ['a', 'b'],
      ['1', '2'],
      ['3', '4'],
    ]);
  });

  it('混合多块', () => {
    const md = '# 标题\n段落\n- a\n- b\n```\ncode\n```';
    const blocks = parseBlocks(md);
    expect(blocks.map((b) => b.kind)).toEqual(['h1', 'p', 'ul', 'pre']);
  });

  it('空输入 → 空数组', () => {
    expect(parseBlocks('')).toEqual([]);
  });
});

describe('renderMarkdown', () => {
  it('行内 code 转义', () => {
    expect(renderMarkdown('`x = 1`')).toContain('<code class="md-inline-code">x = 1</code>');
  });

  it('行内 **bold** 和 *italic*', () => {
    expect(renderMarkdown('**bold**')).toContain('<strong>bold</strong>');
    expect(renderMarkdown('*italic*')).toContain('<em>italic</em>');
  });

  it('行内 [link](url)', () => {
    expect(renderMarkdown('[Go](https://x.com)')).toContain('href="https://x.com"');
  });

  it('XSS 防御：HTML 标签被转义', () => {
    const out = renderMarkdown('<script>alert(1)</script>');
    expect(out).not.toContain('<script>');
    expect(out).toContain('&lt;script&gt;');
  });

  it('完整示例输出非空', () => {
    const out = renderMarkdown('# Hello\n\n**bold** and *italic*\n\n- a\n- b\n\n```\nfoo\n```');
    expect(out.length).toBeGreaterThan(50);
    expect(out).toContain('Hello');
    expect(out).toContain('<strong>bold</strong>');
    expect(out).toContain('<em>italic</em>');
  });

  it('空输入 → 空字符串', () => {
    expect(renderMarkdown('')).toBe('');
  });
});

describe('blockToHtml', () => {
  it('所有 kind 都能安全渲染（不抛异常）', () => {
    const kinds = ['h1', 'h2', 'h3', 'p', 'ul', 'ol', 'pre', 'blockquote', 'hr', 'table'] as const;
    for (const kind of kinds) {
      const b = {
        h1: { kind: 'h1' as const, text: 'x' },
        h2: { kind: 'h2' as const, text: 'x' },
        h3: { kind: 'h3' as const, text: 'x' },
        p: { kind: 'p' as const, text: 'x' },
        ul: { kind: 'ul' as const, items: ['a'] },
        ol: { kind: 'ol' as const, items: ['a'] },
        pre: { kind: 'pre' as const, text: 'x', lang: 'js' },
        blockquote: { kind: 'blockquote' as const, text: 'x' },
        hr: { kind: 'hr' as const },
        table: { kind: 'table' as const, rows: [['a'], ['1']] },
      }[kind];
      expect(() => blockToHtml(b)).not.toThrow();
    }
  });
});
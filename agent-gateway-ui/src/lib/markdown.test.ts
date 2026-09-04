/**
 * markdown.test.ts — 7 用例：round-trip renderMarkdown（Round 11 §ui-b8 + §ui-b9）
 */
import { describe, it, expect } from 'vitest';
import { renderMarkdown } from './markdown';

describe('renderMarkdown: code block with highlight', () => {
  it('javascript fence renders with token classes', () => {
    const md = '```js\nconst x = 42;\n```';
    const html = renderMarkdown(md);
    expect(html).toContain('<pre class="md-pre"');
    expect(html).toContain('tok-kw">const');
    expect(html).toContain('tok-num">42');
  });

  it('python fence recognizes def keyword', () => {
    const md = '```python\ndef hello():\n    return "hi"\n```';
    const html = renderMarkdown(md);
    expect(html).toContain('tok-kw">def');
    expect(html).toContain('tok-fn">hello');
    expect(html).toContain('tok-str">"hi"');
  });

  it('json fence highlights keys and constants', () => {
    const md = '```json\n{"a": 1, "b": null}\n```';
    const html = renderMarkdown(md);
    expect(html).toContain('tok-str">"a"');
    expect(html).toContain('tok-num">1');
    expect(html).toContain('tok-kw">null');
  });

  it('sql fence highlights SELECT/FROM/WHERE', () => {
    const md = '```sql\nSELECT * FROM users WHERE id = 1\n```';
    const html = renderMarkdown(md);
    expect(html).toContain('tok-kw">SELECT');
    expect(html).toContain('tok-kw">FROM');
  });

  it('plain fence without language shows no token markup', () => {
    const md = '```\nplain text\n```';
    const html = renderMarkdown(md);
    expect(html).toContain('<pre class="md-pre"');
    expect(html).toContain('plain text');
    expect(html).not.toContain('tok-');
  });

  it('preserves XSS safety in code blocks', () => {
    const md = '```js\n// <script>alert(1)</script>\n```';
    const html = renderMarkdown(md);
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });
});

describe('renderMarkdown: copy button DOM (§ui-b9)', () => {
  it('every pre block has a .md-copy-btn button', () => {
    const md = '```js\nconst x = 1;\n```\n```py\nprint("hi")\n```';
    const html = renderMarkdown(md);
    const matches = html.match(/<button[^>]*class="md-copy-btn"/g);
    expect(matches).not.toBeNull();
    expect(matches!.length).toBe(2);
  });

  it('button has aria-label for accessibility', () => {
    const html = renderMarkdown('```js\nx=1\n```');
    expect(html).toContain('aria-label="复制代码"');
  });

  it('button placed inside pre block (not outside)', () => {
    const html = renderMarkdown('```js\nx=1\n```');
    const preMatch = html.match(/<pre[^>]*>([\s\S]*?)<\/pre>/);
    expect(preMatch).not.toBeNull();
    expect(preMatch![1]).toContain('md-copy-btn');
  });
});

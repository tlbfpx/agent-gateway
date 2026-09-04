/**
 * highlight.test.ts — 8 用例：6 种语言 + 边界 + 别名 + 转义安全
 */
import { describe, it, expect } from 'vitest';
import { highlight, normalizeLang, tokenize } from './highlight';

describe('normalizeLang', () => {
  it('maps common aliases', () => {
    expect(normalizeLang('js')).toBe('javascript');
    expect(normalizeLang('ts')).toBe('typescript');
    expect(normalizeLang('py')).toBe('python');
    expect(normalizeLang('sh')).toBe('bash');
    expect(normalizeLang('shell')).toBe('bash');
    expect(normalizeLang('json')).toBe('json');
    expect(normalizeLang('SQL')).toBe('sql');
    expect(normalizeLang('')).toBe('text');
    expect(normalizeLang(null)).toBe('text');
    expect(normalizeLang('unknown')).toBe('text');
  });
});

describe('highlight: javascript', () => {
  it('highlights keywords, strings, numbers, comments', () => {
    const code = 'const x = 42; // hi\nfunction f(s){return s}';
    const html = highlight(code, 'js');
    expect(html).toContain('tok-kw">const');
    expect(html).toContain('tok-num">42');
    expect(html).toContain('tok-com">// hi');
    expect(html).toContain('tok-fn">f');
  });

  it('escapes HTML in strings', () => {
    const html = highlight(`var x = "<script>";`, 'js');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });
});

describe('highlight: python', () => {
  it('recognizes python keywords and # comments', () => {
    const html = highlight(`def f(x): # comment\n  return x`, 'py');
    expect(html).toContain('tok-kw">def');
    expect(html).toContain('tok-kw">return');
    expect(html).toContain('tok-com"># comment');
    expect(html).toContain('tok-fn">f');
  });

  it('handles triple-quoted strings', () => {
    const html = highlight(`x = """hello\nworld"""`, 'py');
    expect(html).toContain('"""hello');
  });
});

describe('highlight: bash', () => {
  it('recognizes bash keywords and # comments', () => {
    const html = highlight(`if [ -f x ]; then echo "y"; fi  # done`, 'bash');
    expect(html).toContain('tok-kw">if');
    expect(html).toContain('tok-kw">fi');
    expect(html).toContain('tok-com"># done');
  });
});

describe('highlight: json', () => {
  it('highlights keys, numbers, and constants', () => {
    const html = highlight(`{"a": 1, "b": true, "c": null}`, 'json');
    expect(html).toContain('tok-str">"a"');
    expect(html).toContain('tok-num">1');
    expect(html).toContain('tok-kw">true');
    expect(html).toContain('tok-kw">null');
  });
});

describe('highlight: sql', () => {
  it('recognizes keywords case-insensitively', () => {
    const html = highlight(`SELECT * FROM users WHERE id = 1`, 'sql');
    expect(html).toContain('tok-kw">SELECT');
    expect(html).toContain('tok-kw">FROM');
    expect(html).toContain('tok-kw">WHERE');
    expect(html).toContain('tok-num">1');
  });
});

describe('highlight: text mode', () => {
  it('only escapes, no markup', () => {
    const html = highlight('hello <world>', 'text');
    expect(html).toBe('hello &lt;world&gt;');
  });
});

describe('highlight: edge cases', () => {
  it('empty code', () => {
    expect(highlight('', 'js')).toBe('');
    expect(highlight('', null)).toBe('');
  });

  it('unterminated string still produces output', () => {
    const html = highlight('var x = "unterminated', 'js');
    expect(html).toContain('tok-str');
  });

  it('xss in comment is escaped', () => {
    const html = highlight(`// <script>alert(1)</script>`, 'js');
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });
});

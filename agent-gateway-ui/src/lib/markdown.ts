/**
 * markdown.ts — 轻量 Markdown 渲染（零依赖）
 *
 * 设计目标：
 *   - 覆盖 LLM 输出常见格式（标题、粗斜体、列表、引用、行内/块代码、链接、表格）
 *   - 安全：转义 HTML 避免 XSS
 *   - 输出 React 节点数组，可与代码复制按钮组合
 *
 * 不支持的语法：图片、脚注、数学公式、HTML 嵌入（直接转义）。
 */

/**
 * markdown.ts — 轻量 Markdown 渲染（零依赖）
 *
 * 设计目标：
 *   - 覆盖 LLM 输出常见格式（标题、粗斜体、列表、引用、行内/块代码、链接、表格）
 *   - 安全：转义 HTML 避免 XSS
 *   - 输出 React 节点数组,可与代码复制按钮组合
 *   - Round 11 §ui-b8:代码块支持语法高亮(js/ts/py/bash/json/sql)
 *
 * 不支持的语法：图片、脚注、数学公式、HTML 嵌入（直接转义）。
 */
import { highlight } from './highlight';

export interface MdBlock {
  kind:
    | 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6'
    | 'p'
    | 'ul' | 'ol'
    | 'li'
    | 'pre'
    | 'blockquote'
    | 'hr'
    | 'table'
    | 'code-line';
  text?: string;
  /** 列表项 */
  items?: string[];
  /** 表格：每行 cells */
  rows?: string[][];
  /** 代码块的语言 */
  lang?: string;
}

/** HTML 实体转义 */
export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 行内语法 → HTML（已转义后） */
function renderInlineHtml(s: string): string {
  let out = s;
  // 行内代码 `code`
  out = out.replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>');
  // 粗体 **text**
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  // 斜体 *text*（避开 **）
  out = out.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
  // 链接 [text](url)
  out = out.replace(
    /\[([^\]]+)\]\(([^)\s]+)\)/g,
    '<a class="md-link" href="$2" target="_blank" rel="noreferrer noopener">$1</a>',
  );
  return out;
}

/** 拆分一行表格行：去掉首尾空 cell（`| a | b |` → `['a','b']`） */
function parseTableRow(line: string): string[] {
  const parts = line.split('|').map((c) => c.trim());
  // 去掉首尾空字符串（由首尾 | 产生）
  if (parts.length > 0 && parts[0] === '') parts.shift();
  if (parts.length > 0 && parts[parts.length - 1] === '') parts.pop();
  return parts;
}

/** 分块：按行扫描产出块级结构 */
export function parseBlocks(src: string): MdBlock[] {
  const lines = src.replace(/\r\n/g, '\n').split('\n');
  const blocks: MdBlock[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    // 空行
    if (!line.trim()) {
      i++;
      continue;
    }

    // 分隔线
    if (/^---+$/.test(line.trim())) {
      blocks.push({ kind: 'hr' });
      i++;
      continue;
    }

    // 标题
    const h = line.match(/^(#{1,6})\s+(.+)$/);
    if (h) {
      const level = h[1].length as 1 | 2 | 3 | 4 | 5 | 6;
      blocks.push({ kind: (`h${level}` as MdBlock['kind']), text: h[2] });
      i++;
      continue;
    }

    // 代码块 ```lang\n...\n```
    if (line.startsWith('```')) {
      const lang = line.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      blocks.push({ kind: 'pre', text: codeLines.join('\n'), lang });
      i++; // skip closing ```
      continue;
    }

    // 引用 >
    if (line.startsWith('>')) {
      const buf: string[] = [];
      while (i < lines.length && lines[i].startsWith('>')) {
        buf.push(lines[i].replace(/^>\s?/, ''));
        i++;
      }
      blocks.push({ kind: 'blockquote', text: buf.join('\n') });
      continue;
    }

    // 无序列表
    if (/^[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^[-*]\s+/, ''));
        i++;
      }
      blocks.push({ kind: 'ul', items });
      continue;
    }

    // 有序列表
    if (/^\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s+/, ''));
        i++;
      }
      blocks.push({ kind: 'ol', items });
      continue;
    }

    // 表格 | a | b |
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*[-:]+\s*\|/.test(lines[i + 1])) {
      const headerLine = line;
      i += 2; // 跳过 header 和 separator
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes('|')) {
        rows.push(parseTableRow(lines[i]));
        i++;
      }
      blocks.push({ kind: 'table', rows: [parseTableRow(headerLine), ...rows] });
      continue;
    }

    // 段落：累积直到空行
    const para: string[] = [line];
    i++;
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^(#{1,6}\s|```|>\s?|[-*]\s+|\d+\.\s+|---+$)/.test(lines[i]) &&
      !(lines[i].includes('|') && i + 1 < lines.length && /^\s*\|?\s*[-:]+\s*\|/.test(lines[i + 1]))
    ) {
      para.push(lines[i]);
      i++;
    }
    blocks.push({ kind: 'p', text: para.join('\n') });
  }

  return blocks;
}

/** 渲染单个块为 HTML 字符串（行内已渲染） */
export function blockToHtml(b: MdBlock): string {
  const esc = (s: string) => renderInlineHtml(escapeHtml(s));
  switch (b.kind) {
    case 'h1':
      return `<h1 class="md-h1">${esc(b.text ?? '')}</h1>`;
    case 'h2':
      return `<h2 class="md-h2">${esc(b.text ?? '')}</h2>`;
    case 'h3':
      return `<h3 class="md-h3">${esc(b.text ?? '')}</h3>`;
    case 'h4':
      return `<h4 class="md-h4">${esc(b.text ?? '')}</h4>`;
    case 'h5':
      return `<h5 class="md-h5">${esc(b.text ?? '')}</h5>`;
    case 'h6':
      return `<h6 class="md-h6">${esc(b.text ?? '')}</h6>`;
    case 'p':
      return `<p class="md-p">${esc((b.text ?? '').replace(/\n/g, '<br/>'))}</p>`;
    case 'ul':
      return `<ul class="md-ul">${(b.items ?? []).map((it) => `<li>${esc(it)}</li>`).join('')}</ul>`;
    case 'ol':
      return `<ol class="md-ol">${(b.items ?? []).map((it) => `<li>${esc(it)}</li>`).join('')}</ol>`;
    case 'pre':
      // Round 11 §ui-b8:接入语法高亮(js/ts/py/bash/json/sql)
      // §ui-b9:复制按钮 DOM 注入(原 .md-pre::before 仅显示 lang label,无 button DOM 触发事件委托)
      return `<pre class="md-pre" data-lang="${escapeHtml(b.lang ?? '')}"><code>${highlight(b.text ?? '', b.lang ?? '')}</code><button type="button" class="md-copy-btn" aria-label="复制代码">复制</button></pre>`;
    case 'blockquote':
      return `<blockquote class="md-quote">${esc((b.text ?? '').replace(/\n/g, '<br/>'))}</blockquote>`;
    case 'hr':
      return `<hr class="md-hr"/>`;
    case 'table': {
      const [head, ...rest] = b.rows ?? [];
      return (
        `<table class="md-table"><thead><tr>${(head ?? []).map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>` +
        `<tbody>${rest.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`
      );
    }
    case 'code-line':
      return `<code class="md-inline-code">${esc(b.text ?? '')}</code>`;
    default:
      return '';
  }
}

/** 主入口：源码 → HTML 字符串 */
export function renderMarkdown(src: string): string {
  if (!src) return '';
  return parseBlocks(src).map(blockToHtml).join('\n');
}
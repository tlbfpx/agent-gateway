/**
 * highlight.ts — 零依赖轻量代码语法高亮（Round 11 §ui-b8）
 *
 * 设计目标：
 *   - 覆盖 LLM 输出常见的 6 种语言：JavaScript/TypeScript/Python/Bash/JSON/SQL
 *   - 不引入 prismjs / highlight.js / shiki（避免 100KB+ 体积）
 *   - 自实现关键字/字符串/数字/注释识别 + 简单 token 着色
 *   - 输出带 .tok-* class 的 HTML 字符串,与现有 md-pre 容器兼容
 *
 * 不支持的语法：嵌套模板字符串、Python f-string 内表达式、JSX/TSX、CSS/SCSS。
 */

export type SupportedLang = 'javascript' | 'typescript' | 'python' | 'bash' | 'json' | 'sql' | 'text';

/** 语言别名 → 规范名（接受 ```ts、```py、```sh、```js 等写法） */
export function normalizeLang(raw: string | undefined | null): SupportedLang {
  if (!raw) return 'text';
  const l = raw.trim().toLowerCase();
  if (l === 'js' || l === 'jsx' || l === 'javascript') return 'javascript';
  if (l === 'ts' || l === 'tsx' || l === 'typescript') return 'typescript';
  if (l === 'py' || l === 'python') return 'python';
  if (l === 'sh' || l === 'bash' || l === 'shell' || l === 'zsh') return 'bash';
  if (l === 'json' || l === 'jsonc') return 'json';
  if (l === 'sql') return 'sql';
  return 'text';
}

interface Token {
  type: 'kw' | 'str' | 'num' | 'com' | 'fn' | 'op' | 'punct' | 'plain';
  text: string;
}

const JS_KW = new Set([
  'const', 'let', 'var', 'function', 'class', 'extends', 'new', 'return',
  'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default',
  'break', 'continue', 'try', 'catch', 'finally', 'throw', 'import',
  'from', 'export', 'async', 'await', 'yield', 'typeof', 'instanceof',
  'in', 'of', 'this', 'super', 'static', 'public', 'private', 'protected',
  'true', 'false', 'null', 'undefined', 'void',
]);
const PY_KW = new Set([
  'def', 'class', 'import', 'from', 'as', 'return', 'if', 'elif', 'else',
  'for', 'while', 'break', 'continue', 'pass', 'try', 'except', 'finally',
  'raise', 'with', 'lambda', 'yield', 'global', 'nonlocal', 'True', 'False',
  'None', 'and', 'or', 'not', 'in', 'is',
]);
const BASH_KW = new Set([
  'if', 'then', 'else', 'elif', 'fi', 'for', 'while', 'do', 'done',
  'case', 'esac', 'function', 'return', 'in', 'echo', 'cd', 'export',
  'set', 'unset', 'local', 'readonly',
]);
const SQL_KW = new Set([
  'SELECT', 'FROM', 'WHERE', 'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET',
  'DELETE', 'CREATE', 'TABLE', 'DROP', 'ALTER', 'INDEX', 'VIEW', 'JOIN',
  'INNER', 'LEFT', 'RIGHT', 'OUTER', 'ON', 'AS', 'AND', 'OR', 'NOT',
  'NULL', 'IS', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'ORDER', 'BY', 'GROUP',
  'HAVING', 'LIMIT', 'OFFSET', 'DISTINCT', 'COUNT', 'SUM', 'AVG', 'MIN',
  'MAX', 'UNION', 'ALL', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
]);

function tokenizeJs(code: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i];
    // 行注释
    if (c === '/' && code[i + 1] === '/') {
      const end = code.indexOf('\n', i);
      const stop = end === -1 ? code.length : end;
      out.push({ type: 'com', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 块注释
    if (c === '/' && code[i + 1] === '*') {
      const end = code.indexOf('*/', i + 2);
      const stop = end === -1 ? code.length : end + 2;
      out.push({ type: 'com', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 字符串（单/双/反引号）
    if (c === '"' || c === "'" || c === '`') {
      const quote = c;
      let j = i + 1;
      while (j < code.length && code[j] !== quote) {
        if (code[j] === '\\') j += 2;
        else j++;
      }
      const stop = Math.min(j + 1, code.length);
      out.push({ type: 'str', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 数字
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < code.length && /[0-9.xXa-fA-F_]/.test(code[j])) j++;
      out.push({ type: 'num', text: code.slice(i, j) });
      i = j;
      continue;
    }
    // 标识符
    if (/[A-Za-z_$]/.test(c)) {
      let j = i;
      while (j < code.length && /[A-Za-z0-9_$]/.test(code[j])) j++;
      const word = code.slice(i, j);
      const next = code[j];
      if (JS_KW.has(word)) out.push({ type: 'kw', text: word });
      else if (next === '(') out.push({ type: 'fn', text: word });
      else out.push({ type: 'plain', text: word });
      i = j;
      continue;
    }
    // 操作符 / 标点
    if (/[+\-*/%=<>!&|^~?:]/.test(c)) {
      out.push({ type: 'op', text: c });
      i++;
      continue;
    }
    if (/[(){}\[\];,.]/.test(c)) {
      out.push({ type: 'punct', text: c });
      i++;
      continue;
    }
    // 其他
    out.push({ type: 'plain', text: c });
    i++;
  }
  return out;
}

function tokenizePython(code: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i];
    // 注释
    if (c === '#') {
      const end = code.indexOf('\n', i);
      const stop = end === -1 ? code.length : end;
      out.push({ type: 'com', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 字符串（单/双/三引号）
    if (c === '"' || c === "'") {
      const triple = code.slice(i, i + 3);
      if (triple === c + c + c) {
        const end = code.indexOf(triple, i + 3);
        const stop = end === -1 ? code.length : end + 3;
        out.push({ type: 'str', text: code.slice(i, stop) });
        i = stop;
        continue;
      }
      let j = i + 1;
      while (j < code.length && code[j] !== c) {
        if (code[j] === '\\') j += 2;
        else j++;
      }
      const stop = Math.min(j + 1, code.length);
      out.push({ type: 'str', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 数字
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < code.length && /[0-9.eE_xXa-fA-F]/.test(code[j])) j++;
      out.push({ type: 'num', text: code.slice(i, j) });
      i = j;
      continue;
    }
    // 标识符
    if (/[A-Za-z_]/.test(c)) {
      let j = i;
      while (j < code.length && /[A-Za-z0-9_]/.test(code[j])) j++;
      const word = code.slice(i, j);
      const next = code[j];
      if (PY_KW.has(word)) out.push({ type: 'kw', text: word });
      else if (next === '(') out.push({ type: 'fn', text: word });
      else out.push({ type: 'plain', text: word });
      i = j;
      continue;
    }
    if (/[+\-*/%=<>!&|^~]/.test(c)) {
      out.push({ type: 'op', text: c });
      i++;
      continue;
    }
    if (/[(){}\[\];,.:]/.test(c)) {
      out.push({ type: 'punct', text: c });
      i++;
      continue;
    }
    out.push({ type: 'plain', text: c });
    i++;
  }
  return out;
}

function tokenizeBash(code: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i];
    // 注释
    if (c === '#') {
      const end = code.indexOf('\n', i);
      const stop = end === -1 ? code.length : end;
      out.push({ type: 'com', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 字符串（单/双）
    if (c === '"' || c === "'") {
      const quote = c;
      let j = i + 1;
      while (j < code.length && code[j] !== quote) {
        if (code[j] === '\\') j += 2;
        else j++;
      }
      const stop = Math.min(j + 1, code.length);
      out.push({ type: 'str', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 数字
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < code.length && /[0-9.]/.test(code[j])) j++;
      out.push({ type: 'num', text: code.slice(i, j) });
      i = j;
      continue;
    }
    // 标识符
    if (/[A-Za-z_]/.test(c)) {
      let j = i;
      while (j < code.length && /[A-Za-z0-9_]/.test(code[j])) j++;
      const word = code.slice(i, j);
      const next = code[j];
      if (BASH_KW.has(word)) out.push({ type: 'kw', text: word });
      else if (next === '(') out.push({ type: 'fn', text: word });
      else out.push({ type: 'plain', text: word });
      i = j;
      continue;
    }
    if (/[|&;<>=()]/.test(c)) {
      out.push({ type: 'op', text: c });
      i++;
      continue;
    }
    out.push({ type: 'plain', text: c });
    i++;
  }
  return out;
}

function tokenizeJson(code: string): Token[] {
  // JSON 是简化版 JS：string / number / true|false|null / punctuation
  const out: Token[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i];
    if (c === '"') {
      let j = i + 1;
      while (j < code.length && code[j] !== '"') {
        if (code[j] === '\\') j += 2;
        else j++;
      }
      const stop = Math.min(j + 1, code.length);
      out.push({ type: 'str', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    if (/[0-9-]/.test(c)) {
      let j = i;
      while (j < code.length && /[0-9.eE+-]/.test(code[j])) j++;
      out.push({ type: 'num', text: code.slice(i, j) });
      i = j;
      continue;
    }
    if (/[A-Za-z]/.test(c)) {
      let j = i;
      while (j < code.length && /[A-Za-z]/.test(code[j])) j++;
      const word = code.slice(i, j);
      if (word === 'true' || word === 'false' || word === 'null') {
        out.push({ type: 'kw', text: word });
      } else {
        out.push({ type: 'plain', text: word });
      }
      i = j;
      continue;
    }
    if (/[{}[\],:]/.test(c)) {
      out.push({ type: 'punct', text: c });
      i++;
      continue;
    }
    out.push({ type: 'plain', text: c });
    i++;
  }
  return out;
}

function tokenizeSql(code: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i];
    // 行注释
    if (c === '-' && code[i + 1] === '-') {
      const end = code.indexOf('\n', i);
      const stop = end === -1 ? code.length : end;
      out.push({ type: 'com', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 字符串
    if (c === "'") {
      let j = i + 1;
      while (j < code.length && code[j] !== "'") {
        if (code[j] === '\\') j += 2;
        else j++;
      }
      const stop = Math.min(j + 1, code.length);
      out.push({ type: 'str', text: code.slice(i, stop) });
      i = stop;
      continue;
    }
    // 数字
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < code.length && /[0-9.]/.test(code[j])) j++;
      out.push({ type: 'num', text: code.slice(i, j) });
      i = j;
      continue;
    }
    // 标识符（SQL 关键字大小写不敏感,统一查表）
    if (/[A-Za-z_]/.test(c)) {
      let j = i;
      while (j < code.length && /[A-Za-z0-9_]/.test(code[j])) j++;
      const word = code.slice(i, j);
      if (SQL_KW.has(word.toUpperCase())) {
        out.push({ type: 'kw', text: word });
      } else {
        out.push({ type: 'plain', text: word });
      }
      i = j;
      continue;
    }
    if (/[(),;.<>=!*+/-]/.test(c)) {
      out.push({ type: 'op', text: c });
      i++;
      continue;
    }
    out.push({ type: 'plain', text: c });
    i++;
  }
  return out;
}

export function tokenize(code: string, lang: SupportedLang): Token[] {
  switch (lang) {
    case 'javascript':
    case 'typescript':
      return tokenizeJs(code);
    case 'python':
      return tokenizePython(code);
    case 'bash':
      return tokenizeBash(code);
    case 'json':
      return tokenizeJson(code);
    case 'sql':
      return tokenizeSql(code);
    case 'text':
    default:
      return [{ type: 'plain', text: code }];
  }
}

/** Token → HTML 字符串(用于 dangerouslySetInnerHTML) */
export function tokensToHtml(tokens: Token[]): string {
  return tokens
    .map((t) => {
      const escaped = t.text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
      if (t.type === 'plain') return escaped;
      return `<span class="tok-${t.type}">${escaped}</span>`;
    })
    .join('');
}

/** 主入口：源码 + 语言 → HTML 字符串。 */
export function highlight(code: string, langRaw: string | undefined | null): string {
  const lang = normalizeLang(langRaw);
  if (lang === 'text') {
    // text 模式只做转义,无高亮
    return code
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }
  return tokensToHtml(tokenize(code, lang));
}

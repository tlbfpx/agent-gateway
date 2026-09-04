/**
 * CodeSnippet — 4 语言代码片段展示（cURL / Python / JS / Go）
 *
 * 借鉴 Stripe / Postman / OpenAPI Generator 的 "Code snippet" 体验：
 *   - antd Tabs 切换语言
 *   - 每个 Tab 内含可一键复制的代码块（自管 copy 按钮，便于测试 & 控制 UX）
 *   - 复制成功走 antd message.success
 *
 * 纯展示组件，所有逻辑下沉到 `lib/codegen.ts` 的 generateCode() 纯函数，
 * 这里只负责 React 渲染与 clipboard 写入。
 */
import { useMemo } from 'react';
import { Tabs, Button, message } from 'antd';
import { CopyOutlined, CheckOutlined } from '@ant-design/icons';
import {
  generateCode,
  CODEGEN_LANGS,
  type CodegenLang,
  type CodegenRequest,
} from '../lib/codegen';

const LANG_CN: Record<CodegenLang, string> = {
  curl: 'cURL',
  python: 'Python',
  js: 'JavaScript',
  go: 'Go',
};

export interface CodeSnippetProps {
  request: CodegenRequest;
  /** 默认展示的语言 */
  defaultLang?: CodegenLang;
  /** Tabs 形态：line（默认，适合页面内嵌入） | card（适合抽屉/弹窗顶部） */
  size?: 'small' | 'large';
  /** 是否显示标题栏（默认 false —— 通常由外层 content-card 包裹） */
  showTitle?: boolean;
  /**
   * 测试钩子：覆盖默认的 clipboard 写入行为。
   * 传 `(text, lang) => void`，可用于测试捕获，不真正写入剪贴板。
   * 未传时走 `navigator.clipboard.writeText`（若环境不支持则 fallback 到 textarea+execCommand）。
   */
  onCopy?: (text: string, lang: CodegenLang) => void;
}

/** 计算每个语言的代码字符串；useMemo 避免 request 引用变化外的重复计算 */
function useSnippets(request: CodegenRequest) {
  return useMemo(
    () =>
      CODEGEN_LANGS.reduce<Record<CodegenLang, string>>(
        (acc, lang) => {
          acc[lang] = generateCode(request, lang);
          return acc;
        },
        { curl: '', python: '', js: '', go: '' },
      ),
    [request],
  );
}

/**
 * 兼容性写入剪贴板：navigator.clipboard 优先，回落到 textarea+execCommand。
 * 在 jsdom/老浏览器/受限上下文都能用。
 */
function copyTextToClipboard(text: string): boolean {
  try {
    const nav = typeof navigator !== 'undefined' ? navigator : undefined;
    if (nav?.clipboard?.writeText) {
      nav.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through */
  }
  // Fallback: textarea + execCommand
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    return true;
  } catch {
    return false;
  }
}

export function CodeSnippet({
  request,
  defaultLang = 'curl',
  size = 'small',
  showTitle = false,
  onCopy,
}: CodeSnippetProps) {
  const snippets = useSnippets(request);

  const items = CODEGEN_LANGS.map((lang) => ({
    key: lang,
    label: LANG_CN[lang],
    children: (
      <div
        style={{
          position: 'relative',
          background: '#0F1B3D',
          color: '#E8ECF7',
          padding: 12,
          borderRadius: 6,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          whiteSpace: 'pre',
          overflow: 'auto',
          maxHeight: 360,
        }}
      >
        <Button
          size="small"
          type="text"
          icon={<CopyOutlined style={{ color: '#E8ECF7' }} />}
          data-testid={`code-snippet-copy-${lang}`}
          aria-label={`复制 ${LANG_CN[lang]} 代码`}
          onClick={() => {
            const text = snippets[lang];
            if (onCopy) {
              onCopy(text, lang);
            } else {
              const ok = copyTextToClipboard(text);
              if (ok) message.success(`${LANG_CN[lang]} 代码已复制`);
              else message.error('复制失败，请手动选择文本');
            }
          }}
          style={{
            position: 'absolute',
            top: 6,
            right: 6,
            color: '#E8ECF7',
          }}
        />
        <div style={{ fontSize: 10, opacity: 0.6, marginBottom: 6 }}>{lang}</div>
        <div>{snippets[lang]}</div>
      </div>
    ),
  }));

  return (
    <div data-testid="code-snippet">
      {showTitle && (
        <div
          style={{
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            letterSpacing: 1,
            color: 'var(--text-3)',
            textTransform: 'uppercase',
            marginBottom: 6,
          }}
        >
          代码片段（点击右上角复制）
        </div>
      )}
      <Tabs
        size={size}
        type="line"
        defaultActiveKey={defaultLang}
        items={items}
        data-testid="code-snippet-tabs"
      />
    </div>
  );
}

export default CodeSnippet;
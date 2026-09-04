/**
 * MarkdownView.tsx — Chat 消息 Markdown 渲染 + 代码块复制
 * - 自建 markdown 渲染（src/lib/markdown.ts），零依赖
 * - 每个 <pre> 代码块右上角"复制"按钮（事件委托实现）
 * - 安全：HTML 转义后白名单标签，杜绝 XSS
 */
import { useEffect, useMemo, useRef } from 'react';
import { Button, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { renderMarkdown } from '../../lib/markdown';

interface MarkdownViewProps {
  source: string;
  /** 是否只读（影响复制按钮显示） */
  showCopy?: boolean;
}

export function MarkdownView({ source, showCopy = true }: MarkdownViewProps) {
  const html = useMemo(() => renderMarkdown(source), [source]);
  const ref = useRef<HTMLDivElement>(null);

  // 事件委托：拦截 .md-pre 上的复制按钮点击
  useEffect(() => {
    if (!ref.current || !showCopy) return;
    const root = ref.current;
    const onClick = async (e: MouseEvent) => {
      const target = (e.target as HTMLElement).closest('.md-copy-btn') as HTMLButtonElement | null;
      if (!target) return;
      const pre = target.closest('pre') as HTMLPreElement | null;
      if (!pre) return;
      const code = pre.querySelector('code');
      const text = code?.textContent ?? '';
      try {
        await navigator.clipboard.writeText(text);
        message.success('已复制');
      } catch {
        message.error('复制失败');
      }
    };
    root.addEventListener('click', onClick);
    return () => root.removeEventListener('click', onClick);
  }, [html, showCopy]);

  return (
    <div
      ref={ref}
      className="md-view"
      dangerouslySetInnerHTML={{
        __html:
          html +
          (showCopy ? COPY_BTN_STYLE : ''),
      }}
    />
  );
}

/**
 * 在 <pre> 块注入复制按钮 —— 通过 CSS ::before + JavaScript 注入按钮的方式
 * 更简单方案：把按钮直接插入 DOM。这里用 ::after 装饰 + 事件委托。
 */
const COPY_BTN_STYLE = `
<style>
.md-view { line-height: 1.7; color: var(--text-1); font-size: 13px; }
.md-view > *:first-child { margin-top: 0; }
.md-view > *:last-child { margin-bottom: 0; }
.md-view .md-h1 { font-size: 22px; font-weight: 600; margin: 14px 0 8px; color: var(--text-1); }
.md-view .md-h2 { font-size: 18px; font-weight: 600; margin: 12px 0 6px; padding-bottom: 4px; border-bottom: 1px solid var(--border-thin); }
.md-view .md-h3 { font-size: 15px; font-weight: 600; margin: 10px 0 4px; color: var(--brand-amber); }
.md-view .md-h4 { font-size: 14px; font-weight: 600; margin: 8px 0 4px; }
.md-view .md-p  { margin: 6px 0; }
.md-view .md-ul, .md-view .md-ol { margin: 6px 0; padding-left: 22px; }
.md-view .md-ul li, .md-view .md-ol li { margin: 2px 0; }
.md-view .md-quote { margin: 6px 0; padding: 6px 12px; border-left: 3px solid var(--brand-amber); background: var(--bg-sunken); color: var(--text-2); border-radius: 0 4px 4px 0; }
.md-view .md-hr { border: 0; border-top: 1px dashed var(--border-thin); margin: 12px 0; }
.md-view .md-link { color: var(--ant-primary); text-decoration: none; border-bottom: 1px dashed var(--ant-primary); }
.md-view .md-link:hover { border-bottom-style: solid; }
.md-view .md-inline-code { background: var(--bg-sunken); padding: 1px 6px; border-radius: 4px; font-family: var(--font-mono); font-size: 12px; color: var(--brand-amber); border: 1px solid var(--border-thin); }
.md-view .md-pre { position: relative; background: #0F1B3D; color: #E8ECF7; padding: 12px 14px; border-radius: 6px; overflow-x: auto; margin: 8px 0; font-family: var(--font-mono); font-size: 12px; line-height: 1.5; }
.md-view .md-pre code { background: transparent; padding: 0; color: inherit; border: none; font-family: inherit; }
.md-view .md-pre::before { content: attr(data-lang); position: absolute; top: 6px; right: 10px; font-size: 10px; color: rgba(255,255,255,0.4); text-transform: uppercase; letter-spacing: 1px; pointer-events: none; }
/* Round 11 §ui-b9:复制按钮 DOM(由 markdown.ts 注入);绝对定位右上,hover 才显示 */
.md-view .md-pre .md-copy-btn { position: absolute; top: 4px; right: 4px; padding: 2px 8px; font-size: 11px; line-height: 1.4; background: rgba(255,255,255,0.08); color: rgba(255,255,255,0.7); border: 1px solid rgba(255,255,255,0.15); border-radius: 4px; cursor: pointer; opacity: 0; transition: opacity 0.15s; font-family: var(--font-sans); }
.md-view .md-pre:hover .md-copy-btn { opacity: 1; }
.md-view .md-pre .md-copy-btn:hover { background: rgba(255,255,255,0.18); color: #fff; }
.md-view .md-pre .md-copy-btn:focus-visible { opacity: 1; outline: 2px solid var(--brand-amber); outline-offset: 2px; }
.md-view .md-table { width: 100%; border-collapse: collapse; margin: 8px 0; font-size: 12px; }
.md-view .md-table th, .md-view .md-table td { border: 1px solid var(--border-thin); padding: 6px 10px; text-align: left; }
.md-view .md-table th { background: var(--bg-sunken); font-weight: 500; }
.md-view strong { color: var(--brand-amber); font-weight: 600; }
.md-view em { font-style: italic; color: var(--text-2); }
</style>
`;

/**
 * CodeBlock — 单独的代码块组件（用于逐块渲染+复制）
 * 备选方案：当不信任 markdown 库时，可以用这个手写 <pre>
 */
export function CodeBlock({ code, lang }: { code: string; lang?: string }) {
  return (
    <div style={{ position: 'relative' }}>
      <pre className="md-pre" data-lang={lang ?? ''}>
        <code>{code}</code>
      </pre>
      <Button
        size="small"
        type="text"
        icon={<CopyOutlined />}
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(code);
            message.success('已复制');
          } catch {
            message.error('复制失败');
          }
        }}
        style={{
          position: 'absolute',
          top: 6,
          right: 6,
          color: 'rgba(255,255,255,0.6)',
        }}
        aria-label="复制代码"
      />
    </div>
  );
}
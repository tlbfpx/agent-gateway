/**
 * CodeSnippet.test.tsx — 3 用例：渲染 4 个 Tab + 切换 Tab + 点击复制
 *
 * CodeSnippet 暴露 onCopy 测试钩子：用 onCopy 捕获被请求复制的文本，
 * 避免对 jsdom 25 不实现的 navigator.clipboard.writeText 做桩。
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { CodeSnippet } from './CodeSnippet';
import type { CodegenRequest } from '../lib/codegen';

const SAMPLE_REQ: CodegenRequest = {
  method: 'POST',
  url: 'https://gw.example.com/v1/chat/completions',
  headers: { 'Content-Type': 'application/json' },
  body: { model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }] },
};

describe('CodeSnippet', () => {
  it('renders 4 tabs (curl / python / js / go)', () => {
    render(<CodeSnippet request={SAMPLE_REQ} />);
    const root = screen.getByTestId('code-snippet');
    const tabs = within(root).getByRole('tablist');
    expect(tabs).toBeInTheDocument();
    expect(within(tabs).getByText('cURL')).toBeInTheDocument();
    expect(within(tabs).getByText('Python')).toBeInTheDocument();
    expect(within(tabs).getByText('JavaScript')).toBeInTheDocument();
    expect(within(tabs).getByText('Go')).toBeInTheDocument();
  });

  it('switches active tab and updates visible code', () => {
    render(<CodeSnippet request={SAMPLE_REQ} defaultLang="curl" />);
    const root = screen.getByTestId('code-snippet');

    // 初始：curl 应可见
    expect(within(root).getByText(/curl -X POST/)).toBeInTheDocument();

    // 切到 Python
    fireEvent.click(within(root).getByRole('tab', { name: 'Python' }));
    expect(within(root).getByText(/import requests/)).toBeInTheDocument();
    expect(within(root).getByText(/requests\.request\(/) ).toBeInTheDocument();
  });

  it('clicking the copy icon invokes onCopy with the generated snippet', () => {
    const onCopy = vi.fn();
    render(<CodeSnippet request={SAMPLE_REQ} defaultLang="curl" onCopy={onCopy} />);

    // antd Tabs 默认 destroyOnHidden=true（除非显式设置 false）；
    // 只 active 的 panel 被 mount —— 当前为 cURL，所以只有一个按钮
    const curlCopyBtn = screen.getByTestId('code-snippet-copy-curl');
    expect(curlCopyBtn).toBeInTheDocument();

    fireEvent.click(curlCopyBtn);
    expect(onCopy).toHaveBeenCalledTimes(1);
    const [text, lang] = onCopy.mock.calls[0];
    expect(lang).toBe('curl');
    expect(text).toContain('curl -X POST');
    expect(text).toContain('https://gw.example.com/v1/chat/completions');

    // 切到 Go 再次点击 —— 验证 text 跟随 lang 切换
    const root = screen.getByTestId('code-snippet');
    fireEvent.click(within(root).getByRole('tab', { name: 'Go' }));
    const goCopyBtn = screen.getByTestId('code-snippet-copy-go');
    fireEvent.click(goCopyBtn);
    expect(onCopy).toHaveBeenCalledTimes(2);
    expect(onCopy.mock.calls[1][1]).toBe('go');
    expect(onCopy.mock.calls[1][0]).toContain('http.NewRequest');
  });
});
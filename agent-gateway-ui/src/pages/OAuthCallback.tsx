import { useEffect, useState } from 'react';
import { Alert, Spin, Typography } from 'antd';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { setAdminToken } from '../lib/api/auth';
import { setTenant } from '../lib/request';
import { PageHeader } from '../components/framework/PageHeader';

const { Text, Paragraph } = Typography;

/**
 * /oauth/callback — OIDC 回调落地页（spec 2026-09-05 §sso-oidc §6.4）。
 *
 * <p>流程：
 /ol1 /1. 浏览器 IdP 登录后跳到 <code>/v1/auth/oidc/callback?code=&state=&nonce=</code>
 *   （后端 302 redirect 到本页面，token 在 URL fragment 里）
 *   GET 参数：<code>?returnTo=...</code>（明文）
 *   URL fragment：<code>#token=&tenant=&email=</code>（base64-friendly 不进 server log）
 *   3. 本页面解析 fragment → 写 localStorage → 跳 returnTo
 *
 * <p>fragment 不会发到 server，所以 token 不会写到 nginx access log。
 */
export function OAuthCallback() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string>('');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    // fragment 在 window.location.hash（不带 # 前缀）
    const hash = window.location.hash.startsWith('#')
        ? window.location.hash.substring(1)
        : window.location.hash;
    const frag = new URLSearchParams(hash);
    const token = frag.get('token');
    const tenant = frag.get('tenant');
    const email = frag.get('email');
    const returnTo = params.get('returnTo') || '/admin-users';

    if (!token) {
      setError('URL 中缺少 token 参数；可能 SSO 流程被中断。请重试 /login。');
      return;
    }
    try {
      setAdminToken(token);
      if (tenant) setTenant(tenant);
      if (email) window.localStorage.setItem('agent-gateway.email', email);
      // 清 fragment + 跳 returnTo
      setReady(true);
      window.history.replaceState(null, '', window.location.pathname);
      setTimeout(() => navigate(returnTo, { replace: true }), 100);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存 session 失败');
    }
  }, [params, navigate]);

  if (error) {
    return (
      <>
        <PageHeader eyebrow="SSO" title="登录失败" />
        <Alert
          type="error"
          showIcon
          message="SSO 回调处理失败"
          description={error}
          style={{ maxWidth: 560 }}
        />
        <Paragraph style={{ marginTop: 16 }}>
          <a href="/login">返回登录页</a>
        </Paragraph>
      </>
    );
  }

  return (
    <>
      <PageHeader eyebrow="SSO" title="登录中…" sub="正在保存你的 SSO session" />
      <div style={{ textAlign: 'center', padding: 32 }}>
        <Spin />
        <Paragraph style={{ marginTop: 16 }}>
          <Text type="secondary">{ready ? '正在跳转…' : '处理 OIDC 回调…'}</Text>
        </Paragraph>
      </div>
    </>
  );
}
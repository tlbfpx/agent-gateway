import { useEffect, useState } from 'react';
import { Alert, Button, Space } from 'antd';
import { GiftOutlined, LogoutOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { clearAuth } from '../../lib/request';

/**
 * DemoBanner — Demo 模式全局顶栏（spec 2026-09-04 §demo-mode §9）。
 *
 * 显示条件：当前 tenant 是 demo-* 前缀（即通过 /v1/demo/bootstrap 写入的会话）。
 * 内容：
 *   - 当前 demo 标识 + 24h TTL 倒计时
 *   - 「升级到正式」按钮 → 跳 /settings
 *   - 「退出 Demo」按钮 → 清空本地凭据 + 跳 /demo
 *
 * 注意：expiresAt 由 bootstrap 写入 localStorage；key 形如 agent-gateway.demoExpiresAt。
 */
const DEMO_EXPIRES_KEY = 'agent-gateway.demoExpiresAt';

function readDemoExpiry(): number | null {
  const v = window.localStorage.getItem(DEMO_EXPIRES_KEY);
  if (!v) return null;
  const ts = Date.parse(v);
  return Number.isFinite(ts) ? ts : null;
}

function formatRemaining(ms: number): string {
  if (ms <= 0) return '已过期';
  const totalMin = Math.floor(ms / 60_000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export function DemoBanner() {
  const [active, setActive] = useState(false);
  const [remaining, setRemaining] = useState(0);
  const navigate = useNavigate();

  useEffect(() => {
    const tenant = window.localStorage.getItem('agent-gateway.tenant') ?? '';
    const expiry = readDemoExpiry();
    if (!tenant.startsWith('demo-') || !expiry) {
      setActive(false);
      return;
    }
    setActive(true);
    const tick = () => setRemaining(expiry - Date.now());
    tick();
    const t = setInterval(tick, 60_000);
    return () => clearInterval(t);
  }, []);

  if (!active) return null;

  const onExit = () => {
    clearAuth();
    window.localStorage.removeItem(DEMO_EXPIRES_KEY);
    window.location.href = '/demo';
  };

  return (
    <Alert
      type="warning"
      banner
      showIcon
      icon={<GiftOutlined />}
      message={
        <Space size="middle" wrap>
          <strong>Demo 模式</strong>
          <span>独立租户已就绪 · 剩余 {formatRemaining(remaining)} 自动清理</span>
          <Button
            type="link"
            size="small"
            onClick={() => navigate('/settings')}
            data-testid="demo-upgrade-btn"
          >
            升级到正式 →
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<LogoutOutlined />}
            onClick={onExit}
            data-testid="demo-exit-btn"
          >
            退出 Demo
          </Button>
        </Space>
      }
    />
  );
}
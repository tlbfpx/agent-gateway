/**
 * TenantSwitcher — Header 右上租户切换器
 *
 * - 从 listApiKeys() 拉取已用租户列表（带 fallback 硬编码）
 * - 点击切换后写 localStorage（同步到下一次请求的 X-Tenant-Id）
 * - 切换后会刷新页面（让所有组件拿到新值）
 */
import { useEffect, useState } from 'react';
import { Button, Dropdown, Tag, Spin } from 'antd';
import type { MenuProps } from 'antd';
import { CloudOutlined, SwapOutlined, CheckOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getTenant, setTenant } from '../../lib/request';
import { listApiKeys } from '../../lib/api/keys';

const FALLBACK_TENANTS = [
  { id: 'primary', label: 'primary · 主租户' },
  { id: 'tenant-b', label: 'tenant-b' },
  { id: 'tenant-c', label: 'tenant-c' },
];

export function TenantSwitcher() {
  const [tenants, setTenants] = useState<{ id: string; label: string }[]>(FALLBACK_TENANTS);
  const [current, setCurrent] = useState<string>(getTenant());
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    let mounted = true;
    (async () => {
      try {
        setLoading(true);
        const keys = await listApiKeys();
        if (!mounted) return;
        const set = new Map<string, string>();
        set.set('primary', 'primary · 主租户');
        for (const k of keys) {
          if (!set.has(k.tenant)) set.set(k.tenant, k.tenant);
        }
        setTenants(
          Array.from(set.entries()).map(([id, label]) => ({ id, label })),
        );
      } catch {
        /* fallback */
      } finally {
        if (mounted) setLoading(false);
      }
    })();
    return () => {
      mounted = false;
    };
  }, []);

  const onSwitch = (id: string) => {
    if (id === current) return;
    setTenant(id);
    setCurrent(id);
    // 强制刷新让所有组件拿到新值
    window.location.reload();
  };

  const items: MenuProps['items'] = tenants.map((t) => ({
    key: t.id,
    label: (
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {t.id === current ? <CheckOutlined style={{ color: 'var(--ant-success)' }} /> : null}
        {t.label}
      </span>
    ),
    onClick: () => onSwitch(t.id),
  }));

  const currentLabel = tenants.find((t) => t.id === current)?.label ?? current;

  return (
    <Dropdown menu={{ items }} placement="bottomRight" trigger={['click']}>
      <Button
        type="text"
        size="small"
        icon={loading ? <Spin size="small" /> : <CloudOutlined />}
        style={{
          color: 'var(--text-2)',
          fontSize: 12,
          height: 32,
        }}
        aria-label="切换租户"
      >
        <Space size={4}>
          <span style={{ fontFamily: 'var(--font-mono)' }}>{current}</span>
          <Tag color={current === 'primary' ? 'gold' : 'blue'} style={{ margin: 0, fontSize: 10 }}>
            {currentLabel.split(' · ')[1] ?? current}
          </Tag>
          <SwapOutlined style={{ fontSize: 10, color: 'var(--text-3)' }} />
        </Space>
      </Button>
    </Dropdown>
  );
}

// 临时 import 以满足内联 Space
import { Space } from 'antd';
import { useEffect, useState } from 'react';
import { Card, Space, Tag, Typography, Alert, Spin } from 'antd';
import { CheckCircleTwoTone, CloseCircleTwoTone, MinusCircleTwoTone } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';

const { Text } = Typography;

interface StatusSnapshot {
  status: string;
  version: string;
  buildTimestamp?: string;
  uptimeSeconds: number;
  services: Record<string, { status: string; detail: string }>;
}

const SERVICE_COLOR: Record<string, string> = {
  UP: 'success',
  ENABLED: 'success',
  DISABLED: 'default',
  DOWN: 'error',
  UNKNOWN: 'warning',
};

function ServiceIcon({ status }: { status: string }) {
  if (status === 'UP' || status === 'ENABLED') return <CheckCircleTwoTone twoToneColor="#52c41a" />;
  if (status === 'DOWN') return <CloseCircleTwoTone twoToneColor="#ff4d4f" />;
  return <MinusCircleTwoTone twoToneColor="#faad14" />;
}

function formatUptime(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${m % 60}m`;
  const d = Math.floor(h / 24);
  return `${d}d ${h % 24}h`;
}

/**
 * /status — 运维 status 页面（spec 2026-09-05 §status-page）。
 *
 * 拉 GET /status.json（公开端点，无需鉴权，便于 curl 集成）；
 * 渲染当前版本、运行时长、子系统矩阵。
 * 任何子系统 DOWN 都不会让整页红 — 我们 fail-open：运维看到的是详情，不是被锁在外面。
 */
export function Status() {
  const [snap, setSnap] = useState<StatusSnapshot | null>(null);
  const [error, setError] = useState<string>('');

  useEffect(() => {
    let alive = true;
    fetch('/status.json')
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json() as Promise<StatusSnapshot>;
      })
      .then((s) => {
        if (alive) setSnap(s);
      })
      .catch((e) => {
        if (alive) setError(e.message);
      });
    return () => {
      alive = false;
    };
  }, []);

  if (error) {
    return (
      <>
        <PageHeader eyebrow="Status · 系统状态" title="暂时无法连接" />
        <Alert type="error" message="拉取 /status.json 失败" description={error} showIcon />
      </>
    );
  }

  if (!snap) {
    return (
      <>
        <PageHeader eyebrow="Status · 系统状态" title="加载中…" />
        <div style={{ padding: 32, textAlign: 'center' }}>
          <Spin />
        </div>
      </>
    );
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Status · 系统状态"
        title={`整体 ${snap.status}`}
        sub={`版本 ${snap.version} · 运行时长 ${formatUptime(snap.uptimeSeconds)}`}
      />

      {snap.status !== 'UP' && (
        <Alert
          type="warning"
          showIcon
          message={`系统状态为 ${snap.status}`}
          description="子系统可能存在异常，请看下方详情或联系运维。"
        />
      )}

      <Card title="子系统" data-testid="status-services">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {Object.entries(snap.services).map(([name, info]) => (
            <Space key={name} size="middle" style={{ width: '100%', justifyContent: 'space-between' }}>
              <Space size="small">
                <ServiceIcon status={info.status} />
                <Text strong>{name}</Text>
              </Space>
              <Space size="middle">
                <Tag color={SERVICE_COLOR[info.status] || 'default'}>{info.status}</Tag>
                <Text type="secondary" style={{ fontSize: 13 }}>{info.detail}</Text>
              </Space>
            </Space>
          ))}
        </Space>
      </Card>

      <Card size="small">
        <Space direction="vertical" size={4}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            机器可读端点 <code>GET /status.json</code> · 适用监控探针 / 状态页聚合
          </Text>
          {snap.buildTimestamp && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              构建标识：{snap.buildTimestamp}
            </Text>
          )}
        </Space>
      </Card>
    </Space>
  );
}
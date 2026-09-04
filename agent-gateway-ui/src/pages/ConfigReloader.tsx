import { useEffect, useState, useCallback } from 'react';
import { Row, Col, Card, Tag, Button, Space, Spin, message } from 'antd';
import { ReloadOutlined, CheckCircleTwoTone, ExclamationCircleTwoTone, SyncOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { configStatusAll, configStatusRecent } from '../lib/api/configStatus';

type State = 'SYNCED' | 'RELOADING' | 'FAILED' | 'UNKNOWN';

interface Status {
  name: string;
  state: State;
  lastError: string | null;
  lastSuccessEpochMs: number;
  lastFailEpochMs: number;
}

const stateTag = (s: State) => {
  switch (s) {
    case 'SYNCED':
      return <Tag icon={<CheckCircleTwoTone twoToneColor="#52c41a" />} color="success">synced</Tag>;
    case 'RELOADING':
      return <Tag icon={<SyncOutlined spin />} color="processing">reloading</Tag>;
    case 'FAILED':
      return <Tag icon={<ExclamationCircleTwoTone twoToneColor="#f5222d" />} color="error">failed</Tag>;
    default:
      return <Tag color="default">unknown</Tag>;
  }
};

const fmt = (ms: number) => (ms > 0 ? new Date(ms).toLocaleString() : '—');

export function ConfigReloader() {
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [recent, setRecent] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, r] = await Promise.all([configStatusAll(), configStatusRecent()]);
      setStatuses(s);
      setRecent(r.events ?? []);
    } catch (e: any) {
      message.error('config status load failed: ' + (e?.message ?? e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <div style={{ padding: 24 }}>
      <PageHeader
        eyebrow="Sprint 1 P0"
        title="Config Reloader"
        sub="热重载状态总览 — 文件变更 / Nacos push / K8s ConfigMap 同步更新，无需重启"
        actions={
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            Refresh
          </Button>
        }
      />
      <Row gutter={[16, 16]}>
        {statuses.map((s) => (
          <Col key={s.name} xs={24} sm={12} md={8} lg={6}>
            <Card size="small" title={s.name} extra={stateTag(s.state)}>
              <div style={{ fontSize: 12, color: '#666' }}>
                <div>last synced: {fmt(s.lastSuccessEpochMs)}</div>
                <div>last failed: {fmt(s.lastFailEpochMs)}</div>
                {s.lastError && (
                  <div style={{ color: '#f5222d', marginTop: 4 }}>err: {s.lastError}</div>
                )}
              </div>
            </Card>
          </Col>
        ))}
      </Row>
      <Card style={{ marginTop: 16 }} title="Recent events" size="small">
        {recent.length === 0 ? (
          <Spin />
        ) : (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            {recent.slice().reverse().slice(0, 30).map((e, i) => (
              <div key={i} style={{ fontFamily: 'monospace', fontSize: 12 }}>{e}</div>
            ))}
          </Space>
        )}
      </Card>
    </div>
  );
}
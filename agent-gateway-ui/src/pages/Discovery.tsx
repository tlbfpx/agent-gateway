import { useEffect, useState } from 'react';
import { Row, Col, Tag, Empty, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { listDiscovery, listAgents } from '../lib/api/agents';
import type { AgentInfo } from '../lib/api/agents';

export function Discovery() {
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [d, a] = await Promise.allSettled([listDiscovery(), listAgents()]);
      if (d.status === 'fulfilled') setAgents(d.value);
      else if (a.status === 'fulfilled') setAgents(a.value);
    } catch {
      setAgents([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <>
      <PageHeader
        eyebrow="Discovery · 服务发现"
        title="Agent 注册"
        sub="通过 Nacos 同步的 AgentCard · 心跳状态实时显示"
        actions={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
      />

      {agents.length === 0 ? (
        <div className="content-card">
          <EmptyState variant="no-data" description={loading ? '加载中…' : '暂无 Agent'} />
        </div>
      ) : (
        <Row gutter={[16, 16]}>
          {agents.map((a) => (
            <Col xs={24} md={12} lg={8} key={a.name}>
              <div className="content-card" style={{ height: '100%' }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    marginBottom: 8,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span
                      className={`status-dot ${a.available ? 'success' : 'idle'}`}
                    ></span>
                    <strong>{a.name}</strong>
                  </div>
                  {a.version && <Tag>v{a.version}</Tag>}
                </div>
                <div style={{ color: 'var(--text-3)', fontSize: 13, marginBottom: 12 }}>
                  {a.description || '—'}
                </div>
                {a.skills?.length > 0 && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                    {a.skills.map((s) => (
                      <Tag key={s}>{s}</Tag>
                    ))}
                  </div>
                )}
                {a.endpoint && (
                  <div
                    className="mono"
                    style={{
                      fontSize: 12,
                      color: 'var(--text-3)',
                      marginTop: 12,
                      paddingTop: 8,
                      borderTop: '1px dashed var(--border-thin)',
                    }}
                  >
                    {a.endpoint}
                  </div>
                )}
              </div>
            </Col>
          ))}
        </Row>
      )}
    </>
  );
}
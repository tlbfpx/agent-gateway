import { useEffect, useState } from 'react';
import { Row, Col, Tag, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { getReady } from '../lib/api/health';
import type { ReadyReport, ComponentStatus } from '../lib/api/health';
import { ApiError } from '../lib/request';

interface ComponentView {
  status: string;
  details?: Record<string, unknown>;
}

/** 字符串检查项（"UP"/"EMPTY"）归一为对象形态 */
function toView(v: ComponentStatus): ComponentView {
  return typeof v === 'string' ? { status: v } : v;
}

export function Health() {
  const [report, setReport] = useState<ReadyReport | null>(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setReport(await getReady());
    } catch (err) {
      // NOT_READY 时后端返回 503 + 报告体，request 封装抛 ApiError；取回 body 正常渲染
      if (err instanceof ApiError && err.payload && typeof err.payload === 'object') {
        setReport(err.payload as ReadyReport);
      } else {
        setReport(null);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const components = report?.checks ? Object.entries(report.checks) : [];
  const overallUp = report?.status === 'READY';

  return (
    <>
      <PageHeader
        eyebrow="Readiness · 就绪检查"
        title="系统组件状态"
        sub={`整体 · ${report?.status ?? '—'}`}
        actions={
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            刷新
          </Button>
        }
      />

      {components.length === 0 ? (
        <div className="content-card">
          <EmptyState variant="no-data" description={loading ? '查询中…' : '暂不可用'} />
        </div>
      ) : (
        <Row gutter={[16, 16]}>
          {components.map(([name, comp]) => {
            const view = toView(comp);
            const up = view.status === 'UP';
            return (
              <Col xs={24} sm={12} md={8} lg={6} key={name}>
                <div className="content-card" style={{ height: '100%' }}>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      marginBottom: 12,
                    }}
                  >
                    <strong>{name}</strong>
                    <Tag color={up ? 'success' : 'error'}>{view.status}</Tag>
                  </div>
                  <span className={`status-dot ${up ? 'success' : 'error'}`}></span>
                  <span style={{ color: 'var(--text-3)', fontSize: 13 }}>
                    {up ? '运行正常' : '不可用'}
                  </span>
                  {view.details && (
                    <pre
                      className="mono"
                      style={{
                        marginTop: 12,
                        fontSize: 11,
                        color: 'var(--text-3)',
                        background: 'var(--bg-sunken)',
                        padding: 8,
                        borderRadius: 'var(--r-sm)',
                        maxHeight: 120,
                        overflow: 'auto',
                      }}
                    >
                      {JSON.stringify(view.details, null, 2)}
                    </pre>
                  )}
                </div>
              </Col>
            );
          })}
        </Row>
      )}

      {report && !overallUp && components.length > 0 && (
        <div className="content-card" style={{ marginTop: 16 }}>
          <EmptyState
            variant="no-data"
            description="网关未就绪（NOT_READY）：以下组件检查未全部通过，网关暂不接收流量。"
          />
        </div>
      )}
    </>
  );
}

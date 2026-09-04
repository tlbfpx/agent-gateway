import { useEffect, useState } from 'react';
import { Row, Col, Button, Select, Table, Tag, Space, Popconfirm, Empty, message } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined, RollbackOutlined, DiffOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { listConfigVersions, configDiff, rollbackConfig } from '../lib/api/config';
import type { ConfigVersion } from '../lib/api/config';

export function ConfigHistory() {
  const [name, setName] = useState<'models' | 'api-keys'>('models');
  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [diff, setDiff] = useState<Record<string, [string | null, string | null]> | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setVersions(await listConfigVersions(name));
      setSelected([]);
      setDiff(null);
    } catch {
      setVersions([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [name]);

  const onDiff = async () => {
    if (selected.length !== 2) {
      message.warning('请选择 2 个版本');
      return;
    }
    const [from, to] = [...selected].sort();
    try {
      const d = await configDiff(name, from, to);
      setDiff(d.fields);
    } catch (e: any) {
      message.error(e?.message ?? 'Diff 获取失败');
    }
  };

  const onRollback = async (v: string) => {
    try {
      await rollbackConfig(name, v);
      message.success('已回滚到 ' + v);
      load();
    } catch (e: any) {
      message.error(e?.message ?? '回滚失败');
    }
  };

  const cols: TableColumnsType<ConfigVersion> = [
    { title: '版本', dataIndex: 'version', width: 120, render: (v) => <span className="mono">{v}</span> },
    { title: '时间', dataIndex: 'at', width: 200, render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    { title: '大小', dataIndex: 'size', width: 100, align: 'right', render: (v) => <span className="num">{v} B</span> },
    {
      title: '操作',
      width: 140,
      align: 'right',
      render: (_, row) => (
        <Popconfirm title={`回滚到 ${row.version}？`} onConfirm={() => onRollback(row.version)}>
          <Button type="link" size="small" icon={<RollbackOutlined />}>
            回滚
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Config History · 配置历史"
        title="配置版本与回滚"
        sub={`${name} · ${versions.length} 个版本 · 任意两点对比`}
        actions={
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Space style={{ marginBottom: 12 }}>
            <Select
              value={name}
              onChange={(v) => setName(v)}
              options={[
                { value: 'models', label: 'models 配置' },
                { value: 'api-keys', label: 'api-keys 配置' },
              ]}
              style={{ width: 160 }}
            />
            <Button
              type="primary"
              icon={<DiffOutlined />}
              disabled={selected.length !== 2}
              onClick={onDiff}
            >
              对比所选 ({selected.length})
            </Button>
          </Space>

          <Table
            rowKey="version"
            columns={cols}
            dataSource={versions}
            loading={loading}
            pagination={false}
            rowSelection={{
              selectedRowKeys: selected,
              onChange: (keys) => setSelected(keys.map(String)),
            }}
            locale={{ emptyText: <EmptyState variant="no-data" description="暂无版本" /> }}
          />
        </Col>

        <Col xs={24} lg={10}>
          <div className="content-card">
            <div className="content-card-head">
              <div className="content-card-title">Diff 预览</div>
              {diff && <Tag>{Object.keys(diff).length} 字段</Tag>}
            </div>
            {!diff ? (
              <EmptyState variant="no-result" description="选择两个版本后点击对比" />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {Object.entries(diff).map(([field, [from, to]]) => (
                  <div
                    key={field}
                    style={{
                      padding: 8,
                      borderRadius: 'var(--r-sm)',
                      background: 'var(--bg-sunken)',
                      border: '1px solid var(--border-thin)',
                    }}
                  >
                    <div style={{ fontWeight: 500, fontSize: 12, marginBottom: 4 }}>{field}</div>
                    <div style={{ fontSize: 12, color: 'var(--ant-error)' }}>
                      − {from ?? '(空)'}
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--ant-success)' }}>
                      + {to ?? '(空)'}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </Col>
      </Row>
    </>
  );
}
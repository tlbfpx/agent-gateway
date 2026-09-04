import { useEffect, useState, useCallback } from 'react';
import { Row, Col, Card, Statistic, Table, Button, Tag, Space, Popconfirm, message } from 'antd';
import { ReloadOutlined, ThunderboltOutlined, FireOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { cacheStats, cacheTopQueries, cacheInvalidate, cachePurge } from '../lib/api/cache';
import type { CacheStats, TopQuery } from '../lib/api/cache';

const fmtCents = (c: number) => `¥${(c / 100).toFixed(2)}`;

export function Cache() {
  const [stats, setStats] = useState<CacheStats | null>(null);
  const [tops, setTops] = useState<TopQuery[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, t] = await Promise.all([cacheStats(), cacheTopQueries()]);
      setStats(s);
      setTops(t);
    } catch (e: any) {
      message.error('cache stats load failed: ' + (e?.message ?? e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 10000);
    return () => clearInterval(t);
  }, [load]);

  const doInvalidate = async () => {
    try {
      const r = await cacheInvalidate();
      message.success(`已失效 ${r.removed} 条缓存`);
      load();
    } catch (e: any) {
      message.error('invalidate failed: ' + (e?.message ?? e));
    }
  };

  const doPurge = async () => {
    try {
      const r = await cachePurge(30);
      message.success(`已清理 ${r.removed} 条过期记录`);
      load();
    } catch (e: any) {
      message.error('purge failed: ' + (e?.message ?? e));
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <PageHeader
        eyebrow="Sprint 4 P0"
        title="Semantic Cache"
        sub="语义缓存命中率与成本节省 — text-embedding-3-small + pgvector HNSW"
        actions={
          <Space>
            <Popconfirm title="失效当前租户的所有缓存?" onConfirm={doInvalidate}>
              <Button icon={<FireOutlined />} danger>失效</Button>
            </Popconfirm>
            <Popconfirm title="清理 30 天前过期记录?" onConfirm={doPurge}>
              <Button>清理过期</Button>
            </Popconfirm>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>Refresh</Button>
          </Space>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={6}>
          <Card><Statistic title="命中率" value={stats ? (stats.hitRatio * 100).toFixed(1) : '—'} suffix="%" /></Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card><Statistic title="总命中次数" value={stats?.hits ?? 0} /></Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card><Statistic title="未命中" value={stats?.misses ?? 0} /></Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card>
            <Statistic
              title="节省成本"
              value={stats ? fmtCents(stats.costSavedCents) : '—'}
              prefix={<ThunderboltOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card title="Top 命中 Query" style={{ marginTop: 16 }}>
        <Table
          rowKey="recordId"
          size="small"
          dataSource={tops}
          pagination={{ pageSize: 20 }}
          columns={[
            { title: 'cache_key', dataIndex: 'cacheKey', width: 120, render: (k) => <Tag>{k}</Tag> },
            { title: 'normalized_query', dataIndex: 'normalizedQuery', ellipsis: true },
            { title: 'hits', dataIndex: 'hitCount', width: 80, render: (n) => <strong>{n}</strong> },
            { title: 'saved', dataIndex: 'costSavedCents', width: 100, render: (n) => fmtCents(n) },
          ]}
        />
      </Card>
    </div>
  );
}
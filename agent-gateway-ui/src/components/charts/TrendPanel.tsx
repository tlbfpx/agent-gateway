/**
 * TrendPanel — 指标趋势区(spec 2026-08-19 §6.2)
 *
 * 4 张折线图(请求数/平均延迟/错误数/Token)来自 metrics_samples 分桶聚合;
 * 最近 1h 错误 trace Top 10 一键跳转瀑布图。
 * 未配置持久化存储(503)时整区隐藏(Dashboard 其余卡片不受影响)。
 */
import { useEffect, useState } from 'react';
import { Button, Col, Row, Space, Typography } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { AreaBarChart, type ChartPoint } from './AreaBarChart';
import { getMetricSeries, listTraces, type MetricSeries, type TraceSummary } from '../../lib/api/traces';
import { ApiError } from '../../lib/request';

const { Text } = Typography;

function toPoints(s: MetricSeries | null): ChartPoint[] {
  if (!s) return [];
  return s.points.map((p) => ({ t: p.bucketStart, n: round(p.sum) }));
}

function round(v: number): number {
  if (v >= 100) return Math.round(v);
  return Math.round(v * 10) / 10;
}

/** 平均延迟:total_ms / count 按桶对齐相除。 */
function latencyPoints(countS: MetricSeries | null, totalS: MetricSeries | null): ChartPoint[] {
  if (!countS || !totalS) return [];
  const totalByBucket = new Map(totalS.points.map((p) => [p.bucketStart, p.sum]));
  return countS.points
    .map((p) => ({
      t: p.bucketStart,
      n: p.sum > 0 ? round((totalByBucket.get(p.bucketStart) ?? 0) / p.sum) : 0,
    }))
    .filter((p) => p.n > 0 || p.t === countS.points[0]?.bucketStart);
}

function ChartCard({ title, unit, points }: { title: string; unit: string; points: ChartPoint[] }) {
  return (
    <div
      style={{
        padding: '12px 16px',
        background: 'var(--bg-surface, #111)',
        border: '1px solid var(--border-thin, #333)',
        borderRadius: 8,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <Text strong style={{ fontSize: 13 }}>{title}</Text>
        <Text type="secondary" style={{ fontSize: 11 }}>{unit}</Text>
      </div>
      {points.length > 0 ? (
        <AreaBarChart points={points} />
      ) : (
        <div style={{ height: 230, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text type="secondary">暂无数据</Text>
        </div>
      )}
    </div>
  );
}

export function TrendPanel() {
  const navigate = useNavigate();
  const [requests, setRequests] = useState<MetricSeries | null>(null);
  const [latencyCount, setLatencyCount] = useState<MetricSeries | null>(null);
  const [latencyTotal, setLatencyTotal] = useState<MetricSeries | null>(null);
  const [errors, setErrors] = useState<MetricSeries | null>(null);
  const [tokensIn, setTokensIn] = useState<MetricSeries | null>(null);
  const [errorTraces, setErrorTraces] = useState<TraceSummary[]>([]);
  const [available, setAvailable] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [req, latC, latT, err, tok, traces] = await Promise.all([
          getMetricSeries('chat.requests', '7d'),
          getMetricSeries('chat.latency.count', '7d'),
          getMetricSeries('chat.latency.total_ms', '7d'),
          getMetricSeries('chat.errors', '7d'),
          getMetricSeries('llm.tokens.in', '7d'),
          listTraces({ range: '1h', errorOnly: true, limit: 10 }),
        ]);
        if (cancelled) return;
        setRequests(req);
        setLatencyCount(latC);
        setLatencyTotal(latT);
        setErrors(err);
        setTokensIn(tok);
        setErrorTraces(traces);
      } catch (e) {
        if (e instanceof ApiError && (e.status === 503 || e.message.includes('持久化存储'))) {
          setAvailable(false);  // 未配置 PG:整区隐藏
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (!available) return null;

  return (
    <div style={{ marginBottom: 20 }}>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <ChartCard title="请求量 · 7 天" unit="次/桶" points={toPoints(requests)} />
        </Col>
        <Col xs={24} lg={12}>
          <ChartCard title="平均延迟 · 7 天" unit="ms" points={latencyPoints(latencyCount, latencyTotal)} />
        </Col>
        <Col xs={24} lg={12}>
          <ChartCard title="错误 · 7 天" unit="次/桶" points={toPoints(errors)} />
        </Col>
        <Col xs={24} lg={12}>
          <ChartCard title="输入 Token · 7 天" unit="token/桶" points={toPoints(tokensIn)} />
        </Col>
      </Row>

      {errorTraces.length > 0 && (
        <div
          style={{
            marginTop: 12,
            padding: '10px 16px',
            background: 'rgba(255, 77, 79, 0.05)',
            border: '1px solid rgba(255, 77, 79, 0.25)',
            borderRadius: 8,
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            flexWrap: 'wrap',
          }}
        >
          <Space>
            <WarningOutlined style={{ color: '#ff4d4f' }} />
            <Text strong>最近 1 小时错误链路 {errorTraces.length} 条</Text>
          </Space>
          <Space wrap>
            {errorTraces.slice(0, 5).map((t) => (
              <Button
                key={t.traceId}
                size="small"
                danger
                type="text"
                onClick={() => navigate(`/traces?traceId=${t.traceId}`)}
              >
                {t.traceId.slice(0, 12)}… · {Math.round(t.totalDurationMs)}ms
              </Button>
            ))}
          </Space>
        </div>
      )}
    </div>
  );
}

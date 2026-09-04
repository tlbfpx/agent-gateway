import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag } from 'antd';
import { LikeOutlined, DislikeOutlined, MessageOutlined } from '@ant-design/icons';
import { getFeedbackSummary, type FeedbackSummary } from '../../lib/api/feedback';

interface FeedbackSummaryCardProps {
  tenant?: string;
  model?: string;
}

/**
 * 反馈统计卡（运营仪表盘使用）。
 * 4 张卡片：总数 / 正面 / 负面 / 备注率
 * + 2 张表：模型分布 / Top 标签
 */
export function FeedbackSummaryCard({ tenant = 'au', model }: FeedbackSummaryCardProps) {
  const [summary, setSummary] = useState<FeedbackSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    getFeedbackSummary({ tenant, model })
      .then((s) => setSummary(s))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [tenant, model]);

  if (error) {
    return (
      <Card title="反馈统计" loading={loading}>
        <Tag color="red">加载失败：{error}</Tag>
      </Card>
    );
  }

  return (
    <Card title="反馈统计" loading={loading}>
      {summary && (
        <>
          <Row gutter={16}>
            <Col span={6}>
              <Statistic title="总数" value={summary.total} />
            </Col>
            <Col span={6}>
              <Statistic
                title="👍 正面"
                value={summary.positive}
                valueStyle={{ color: '#3f8600' }}
                prefix={<LikeOutlined />}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="👎 负面"
                value={summary.negative}
                valueStyle={{ color: '#cf1322' }}
                prefix={<DislikeOutlined />}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="含备注"
                value={summary.withComment}
                suffix={`/ ${summary.total}`}
                prefix={<MessageOutlined />}
              />
            </Col>
          </Row>
          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col span={12}>
              <Table
                size="small"
                title={() => '模型分布'}
                rowKey="model"
                dataSource={summary.byModel}
                pagination={false}
                columns={[
                  { title: '模型', dataIndex: 'model' },
                  { title: '总数', dataIndex: 'count' },
                  { title: '👍', dataIndex: 'positive' },
                  { title: '👎', dataIndex: 'negative' },
                ]}
              />
            </Col>
            <Col span={12}>
              <Table
                size="small"
                title={() => 'Top 标签'}
                rowKey="tag"
                dataSource={summary.topTags}
                pagination={false}
                columns={[
                  { title: '标签', dataIndex: 'tag' },
                  { title: '次数', dataIndex: 'count' },
                ]}
              />
            </Col>
          </Row>
        </>
      )}
    </Card>
  );
}

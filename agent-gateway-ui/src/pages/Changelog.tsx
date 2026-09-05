import { useEffect, useState } from 'react';
import { Card, Space, Tag, Typography, Spin, Alert } from 'antd';
import { RocketOutlined, BugOutlined, ToolOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';

const { Text, Paragraph } = Typography;

interface Release {
  tag: string;
  date: string;
  sections: Record<string, string[]>;
}

interface ChangelogResponse {
  releases: Release[];
  raw: string;
}

const SECTION_META: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  features: { label: '✨ New Features', color: 'green', icon: <RocketOutlined /> },
  fixes: { label: '🐛 Bug Fixes', color: 'red', icon: <BugOutlined /> },
  internal: { label: '📦 Internal', color: 'default', icon: <ToolOutlined /> },
  breaking: { label: '⚠️ Breaking', color: 'orange', icon: <ThunderboltOutlined /> },
  performance: { label: '🚀 Performance', color: 'blue', icon: <ThunderboltOutlined /> },
};

/**
 * /changelog — 产品变更日志（spec 2026-09-05 §changelog §3）。
 *
 * 后端 GET /v1/changelog 解析 CHANGELOG.md（classpath:CHANGELOG.md）成结构化 JSON；
 * 这里按 release 倒序渲染，每节折叠成 Card。
 */
export function Changelog() {
  const [data, setData] = useState<ChangelogResponse | null>(null);
  const [error, setError] = useState<string>('');

  useEffect(() => {
    fetch('/v1/changelog')
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json() as Promise<ChangelogResponse>;
      })
      .then(setData)
      .catch((e) => setError(e.message));
  }, []);

  if (error) {
    return (
      <>
        <PageHeader eyebrow="Changelog" title="加载失败" />
        <Alert type="error" message={error} showIcon />
      </>
    );
  }

  if (!data) {
    return (
      <>
        <PageHeader eyebrow="Changelog" title="加载中…" />
        <div style={{ padding: 32, textAlign: 'center' }}>
          <Spin />
        </div>
      </>
    );
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Changelog"
        title="产品变更日志"
        sub={`共 ${data.releases.length} 个 release · 关注进度与升级路径`}
      />

      {data.releases.length === 0 && (
        <Alert type="info" message="暂无可显示的 release" />
      )}

      {data.releases.map((rel) => (
        <Card
          key={rel.tag}
          title={
            <Space>
              <Text strong style={{ fontSize: 18 }}>{rel.tag}</Text>
              <Tag color="blue">{rel.date}</Tag>
              {rel.tag === 'Unreleased' && <Tag color="orange">尚未发布</Tag>}
            </Space>
          }
          data-testid={`changelog-release-${rel.tag}`}
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            {Object.entries(rel.sections).map((([key, items]) => {
              const meta = SECTION_META[key] ?? {
                label: key, color: 'default', icon: <ToolOutlined />,
              };
              return (
                <div key={key} data-testid={`changelog-section-${key}`}>
                  <Space size="small" style={{ marginBottom: 8 }}>
                    {meta.icon}
                    <Text strong>{meta.label}</Text>
                    <Tag>{items.length}</Tag>
                  </Space>
                  <ul style={{ marginTop: 4, paddingLeft: 20, lineHeight: 2 }}>
                    {items.map((it: string, idx: number) => (
                      <li key={idx}>
                        <Text>{it}</Text>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            }))}
          </Space>
        </Card>
      ))}

      <Paragraph type="secondary" style={{ textAlign: 'center', margin: 0, fontSize: 12 }}>
        升级路径：每个 bullet 都是独立 atomic commit，按需 revert。
      </Paragraph>
    </Space>
  );
}
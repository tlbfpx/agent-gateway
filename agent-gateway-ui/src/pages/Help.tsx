/**
 * Help — 帮助中心 / 快捷键 / FAQ / 版本日志
 *
 * 自包含页：内容写在 TS 数据里（不引 CMS），便于后续替换为远程文档。
 */
import { useEffect, useState } from 'react';
import {
  Row,
  Col,
  Card,
  Tag,
  Tabs,
  Collapse,
  Empty,
  Typography,
  Space,
  Anchor,
} from 'antd';
import {
  KeyOutlined,
  ThunderboltOutlined,
  QuestionCircleOutlined,
  HistoryOutlined,
  BulbOutlined,
  CodeOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';

const { Title, Paragraph } = Typography;

interface Shortcut {
  keys: string[];
  desc: string;
  group: 'navigation' | 'chat' | 'list';
}

const SHORTCUTS: Shortcut[] = [
  { keys: ['⌘', 'K'], desc: '全局搜索（菜单 / 模型 / Key / Agent）', group: 'navigation' },
  { keys: ['Esc'], desc: '关闭弹窗 / 退出当前输入', group: 'navigation' },
  { keys: ['↑', '↓'], desc: '在命令面板 / 表单中上下移动', group: 'navigation' },
  { keys: ['↵'], desc: '确认 / 触发选中项', group: 'navigation' },
  { keys: ['Enter'], desc: 'Chat 发送消息（Shift+Enter 换行）', group: 'chat' },
  { keys: ['Shift', 'Enter'], desc: '换行（Chat 输入框）', group: 'chat' },
  { keys: ['/'], desc: '搜索框聚焦（多数列表）', group: 'list' },
];

interface FaqItem {
  q: string;
  a: string;
}

const FAQ: FaqItem[] = [
  {
    q: '如何签发第一个 API Key？',
    a: '进入「API Key」页 → 选租户 → 选模型白名单 → 签发。系统会一次性返回 key 明文，请妥善保存。',
  },
  {
    q: '灰度策略是如何生效的？',
    a: '在「模型管理」点击「灰度」按钮，调整权重滑块。同 grayGroup 的模型按权重加权分流。',
  },
  {
    q: '如何切换租户？',
    a: '点击右上角租户标签 → 选目标租户。切换后所有请求的 X-Tenant-Id 立即生效。',
  },
  {
    q: 'Webhook 投递失败怎么办？',
    a: '查看「Webhook → 死信队列」了解重试情况；也可点击「测试」按钮手动重发。',
  },
  {
    q: '为什么 Dashboard 数字和我预期不同？',
    a: '当 metrics 实时接口未上线时，Dashboard 从 audit 派生近似数据（带「◐ 派生」Tag）。',
  },
  {
    q: '如何接入飞书/钉钉告警？',
    a: '进入「告警中心」→「新建规则」，在「通知通道」选飞书/钉钉，填入 webhook URL。',
  },
  {
    q: 'OpenAPI 文档在哪？',
    a: '进入「开发者 → API 浏览器」，自研 viewer 支持展开端点详情 + Try it 真发请求。',
  },
  {
    q: '如何接入公司现有的 LangChain / Spring AI？',
    a: '文档见 docs/superpowers/specs/2026-08-12-agent-gateway-design.md §3.3 / §14.',
  },
];

interface ChangelogEntry {
  version: string;
  date: string;
  highlights: string[];
  type: 'feature' | 'fix' | 'improvement';
}

const CHANGELOG: ChangelogEntry[] = [
  {
    version: 'v0.6.0',
    date: '2026-08-17',
    highlights: [
      '新增 6 个菜单：成本中心 / 限流监控 / 策略中心 / API 浏览器 / 告警中心 / 帮助',
      '全局 ⌘K 命令面板 + 主题切换（明/暗/系统）',
      'Chat Markdown 渲染 + 消息操作（复制 / 重试 / 分享）',
      '真实 metrics/告警 接入 + 降级聚合',
      'Onboarding 3 步引导',
    ],
    type: 'feature',
  },
  {
    version: 'v0.5.0',
    date: '2026-08-12',
    highlights: [
      '左菜单 13 → 15 个页面',
      '生产级测试基础设施（58 测试）',
      'Chat race condition 修复',
      'GitHub Actions CI 流水线',
    ],
    type: 'improvement',
  },
  {
    version: 'v0.4.0',
    date: '2026-08-05',
    highlights: [
      '多模型 + 灰度 + failover',
      'Session 存储（InMemory / Redis）',
      'A2A 协议 + Nacos 发现',
    ],
    type: 'feature',
  },
  {
    version: 'v0.3.0',
    date: '2026-07-20',
    highlights: [
      'API Key 双通道（签发/撤销）',
      '审计日志 + Webhook 推送',
      'OpenAPI 3.0 导出',
    ],
    type: 'feature',
  },
];

const QUICK_LINKS = [
  { href: '/api-keys', icon: <KeyOutlined />, title: '签发 API Key', desc: '为租户发放第一个 Key' },
  { href: '/models', icon: <ThunderboltOutlined />, title: '注册模型', desc: '配置 OpenAI / Claude / DeepSeek' },
  { href: '/chat', icon: <RocketOutlined />, title: '第一次对话', desc: '在 Chat 测试页发送消息' },
  { href: '/api', icon: <CodeOutlined />, title: 'API 浏览器', desc: '查看 + 试用所有端点' },
  { href: '/alerts', icon: <BulbOutlined />, title: '告警中心', desc: '配置飞书/钉钉通知' },
];

export function Help() {
  const [tab, setTab] = useState<'shortcut' | 'faq' | 'changelog'>('shortcut');

  // 打开时让浏览器自动滚到顶部
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [tab]);

  return (
    <>
      <PageHeader
        eyebrow="Help · 帮助"
        title="帮助中心"
        sub="快捷键 · 常见问题 · 版本日志 · 快速入口"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card variant="borderless" style={{ background: 'var(--bg-surface)' }}>
            <Tabs
              activeKey={tab}
              onChange={(k) => setTab(k as typeof tab)}
              items={[
                { key: 'shortcut', label: <><KeyOutlined /> 快捷键</> },
                { key: 'faq', label: <><QuestionCircleOutlined /> 常见问题</> },
                { key: 'changelog', label: <><HistoryOutlined /> 版本日志</> },
              ]}
            />

            {tab === 'shortcut' && (
              <div>
                {(['navigation', 'chat', 'list'] as const).map((g) => (
                  <div key={g} style={{ marginBottom: 16 }}>
                    <Title level={5} style={{ marginTop: 0 }}>
                      {g === 'navigation' ? '导航' : g === 'chat' ? 'Chat' : '列表'}
                    </Title>
                    <Space direction="vertical" style={{ width: '100%' }} size={8}>
                      {SHORTCUTS.filter((s) => s.group === g).map((s) => (
                        <div
                          key={s.desc}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            padding: 12,
                            background: 'var(--bg-sunken)',
                            borderRadius: 6,
                            border: '1px solid var(--border-thin)',
                          }}
                        >
                          <span style={{ flex: 1, fontSize: 13 }}>{s.desc}</span>
                          <Space size={4}>
                            {s.keys.map((k) => (
                              <kbd
                                key={k}
                                className="mono"
                                style={{
                                  padding: '2px 8px',
                                  border: '1px solid var(--border-thin)',
                                  borderRadius: 4,
                                  background: 'var(--bg-surface)',
                                  fontSize: 11,
                                  color: 'var(--text-2)',
                                }}
                              >
                                {k}
                              </kbd>
                            ))}
                          </Space>
                        </div>
                      ))}
                    </Space>
                  </div>
                ))}
              </div>
            )}

            {tab === 'faq' && (
              <Collapse
                ghost
                items={FAQ.map((f, i) => ({
                  key: String(i),
                  label: <strong>{f.q}</strong>,
                  children: <Paragraph style={{ color: 'var(--text-2)', margin: 0 }}>{f.a}</Paragraph>,
                }))}
              />
            )}

            {tab === 'changelog' && (
              <div style={{ position: 'relative', paddingLeft: 24 }}>
                <div
                  style={{
                    position: 'absolute',
                    left: 8,
                    top: 0,
                    bottom: 0,
                    width: 2,
                    background: 'var(--border-thin)',
                  }}
                />
                {CHANGELOG.map((e) => (
                  <div key={e.version} style={{ marginBottom: 24, position: 'relative' }}>
                    <span
                      style={{
                        position: 'absolute',
                        left: -20,
                        top: 8,
                        width: 12,
                        height: 12,
                        borderRadius: '50%',
                        background:
                          e.type === 'feature'
                            ? 'var(--brand-amber)'
                            : e.type === 'fix'
                              ? 'var(--ant-error)'
                              : 'var(--ant-primary)',
                      }}
                    />
                    <Space style={{ marginBottom: 8 }}>
                      <Tag color="gold" style={{ margin: 0 }}>
                        {e.version}
                      </Tag>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)' }}>
                        {e.date}
                      </span>
                    </Space>
                    <ul style={{ margin: 0, paddingLeft: 20, color: 'var(--text-2)' }}>
                      {e.highlights.map((h, i) => (
                        <li key={i} style={{ marginBottom: 4 }}>
                          {h}
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card
            title={<><RocketOutlined /> 快速入口</>}
            variant="borderless"
            style={{ background: 'var(--bg-surface)' }}
          >
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              {QUICK_LINKS.map((q) => (
                <a
                  key={q.href}
                  href={q.href}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: 12,
                    background: 'var(--bg-sunken)',
                    borderRadius: 6,
                    border: '1px solid var(--border-thin)',
                    textDecoration: 'none',
                    color: 'var(--text-1)',
                  }}
                >
                  <span style={{ color: 'var(--brand-amber)', fontSize: 18, marginRight: 12 }}>
                    {q.icon}
                  </span>
                  <div>
                    <strong style={{ fontSize: 13 }}>{q.title}</strong>
                    <div style={{ fontSize: 11, color: 'var(--text-3)' }}>{q.desc}</div>
                  </div>
                </a>
              ))}
            </Space>
          </Card>

          <Card
            title={<><CodeOutlined /> 文档入口</>}
            variant="borderless"
            style={{ background: 'var(--bg-surface)', marginTop: 16 }}
          >
            <Paragraph style={{ fontSize: 12, color: 'var(--text-3)' }}>
              完整设计文档见仓库：docs/superpowers/specs/2026-08-12-agent-gateway-design.md
            </Paragraph>
            <Anchor
              items={[
                { key: 'spec', href: '/docs/specs/2026-08-12-agent-gateway-design.md', title: '设计 spec (§1-29)' },
                { key: 'plans', href: '/docs/plans/2026-08-12-foundation.md', title: '实现计划' },
                { key: 'changes', href: '/openspec/changes', title: '变更记录 (OpenSpec)' },
                { key: 'agents', href: '/AGENTS.md', title: '协同规范 AGENTS.md' },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </>
  );
}

void Empty;
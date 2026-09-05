import { useEffect, useState } from 'react';
import { Card, Space, Tag, Typography, Button, Progress, Alert, Tooltip } from 'antd';
import {
  CheckCircleTwoTone,
  ApiOutlined,
  MessageOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/framework/PageHeader';
import { getApiKey, getAdminToken, getTenant } from '../lib/request';

const { Text, Paragraph } = Typography;

interface Step {
  key: string;
  title: string;
  desc: string;
  /** 页内可执行跳转；null 表示无需跳转（已自动完成） */
  to?: string;
  /** 检测此步是否已完成的钩子（基于 localStorage / 路由查询） */
  done: () => boolean;
}

const STEPS: Step[] = [
  {
    key: 'signup',
    title: '注册账号',
    desc: '邮箱 + 公司名 + 密码（≥8 位）。10 秒开通独立租户。',
    to: '/signup',
    done: () => !!getAdminToken(),
  },
  {
    key: 'create-key',
    title: '签发首把 API Key',
    desc: '进入「设置」一键签发。所有功能都靠 Key 调用（chat/feedback/metrics/cache）。',
    to: '/settings',
    done: () => !!getApiKey(),
  },
  {
    key: 'try-chat',
    title: '试一次对话',
    desc: '进入「对话」页，选 echo-agent（演示用），发送「你好」。能看到完整 SSE 流式响应。',
    to: '/chat',
    done: () => false, // 没法检测，留作可选勾选
  },
  {
    key: 'audit',
    title: '查看审计日志',
    desc: '刚才的 chat 调用会自动记录到「审计」页。SOC2 合规：右上角可导出 CSV。',
    to: '/audit',
    done: () => false,
  },
  {
    key: 'sso',
    title: '（可选）接入企业 SSO',
    desc: 'Azure AD / Okta / Auth0 / Google 全部支持。5 分钟接入指南在 docs/operators/OIDC.md。',
    to: '/login',
    done: () => false,
  },
];

const PROGRESS_KEY = 'agent-gateway.onboardingSteps';

/**
 * /getting-started — 新租户 onboarding 清单（spec 2026-09-05 §onboarding）。
 *
 * 检测已完成步骤（localStorage / 当前路径），未做的标高亮 + 跳转。
 * 用户可手动勾选「我做了」作为记忆（写 localStorage）。
 */
export function GettingStarted() {
  const [done, setDone] = useState<Record<string, boolean>>({});

  useEffect(() => {
    const fromLs: Record<string, boolean> = (() => {
      try {
        return JSON.parse(localStorage.getItem(PROGRESS_KEY) ?? '{}');
      } catch {
        return {};
      }
    })();
    const computed: Record<string, boolean> = {};
    STEPS.forEach((s) => {
      computed[s.key] = s.done() || !!fromLs[s.key];
    });
    setDone(computed);
  }, []);

  const toggle = (key: string) => {
    setDone((prev) => {
      const next = { ...prev, [key]: !prev[key] };
      try {
        localStorage.setItem(PROGRESS_KEY, JSON.stringify(next));
      } catch {
        /* ignore */
      }
      return next;
    });
  };

  const doneCount = STEPS.filter((s) => done[s.key]).length;
  const pct = Math.round((doneCount / STEPS.length) * 100);

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Onboarding"
        title="快速上手"
        sub="按这 5 步走完，10 分钟解锁全部能力"
      />

      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Text strong>已完成 {doneCount}/{STEPS.length} 步</Text>
            <Tag color={pct === 100 ? 'success' : pct >= 50 ? 'processing' : 'default'}>
              {pct === 100 ? '🎉 全部完成' : `${pct}%`}
            </Tag>
          </Space>
          <Progress
            percent={pct}
            status={pct === 100 ? 'success' : 'active'}
            strokeColor={pct === 100 ? '#52c41a' : '#1677ff'}
          />
          {pct === 100 && (
            <Alert
              type="success"
              showIcon
              message="全部跑完！"
              description={
                <Space direction="vertical">
                  <span>你的租户已就绪，可以开始把 agent 接入业务系统了。</span>
                  <Link to="/dashboard">跳到仪表盘 →</Link>
                </Space>
              }
            />
          )}
        </Space>
      </Card>

      {STEPS.map((step, i) => {
        const finished = done[step.key];
        return (
          <Card
            key={step.key}
            data-testid={`getting-started-step-${step.key}`}
            style={{
              borderColor: finished ? '#b7eb8f' : undefined,
              background: finished ? '#f6ffed' : undefined,
            }}
          >
            <Space style={{ width: '100%' }} size="middle" align="start">
              <Tooltip title={finished ? '已完成' : '点击右侧按钮标记完成'}>
                <Button
                  type="text"
                  size="large"
                  shape="circle"
                  onClick={() => toggle(step.key)}
                  icon={
                    finished ? (
                      <CheckCircleTwoTone twoToneColor="#52c41a" />
                    ) : (
                      <Text type="secondary">{i + 1}</Text>
                    )
                  }
                  data-testid={`getting-started-toggle-${step.key}`}
                />
              </Tooltip>
              <Space direction="vertical" size={4} style={{ flex: 1 }}>
                <Space size="small">
                  <Text strong style={{ fontSize: 16 }}>{step.title}</Text>
                  {finished && <Tag color="success">已完成</Tag>}
                </Space>
                <Paragraph type="secondary" style={{ margin: 0 }}>{step.desc}</Paragraph>
              </Space>
              {step.to && (
                <Link to={step.to}>
                  <Button type={finished ? 'default' : 'primary'} icon={<RocketOutlined />}>
                    去操作
                  </Button>
                </Link>
              )}
            </Space>
          </Card>
        );
      })}

      <Alert
        type="info"
        showIcon
        message="需要更多帮助？"
        description={
          <Space split={<Text type="secondary">|</Text>}>
            <Link to="/changelog">查看更新日志</Link>
            <Link to="/status">系统状态</Link>
            <Link to="/help">帮助中心</Link>
          </Space>
        }
      />
    </Space>
  );
}
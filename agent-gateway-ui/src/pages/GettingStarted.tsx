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
import { useT } from '../lib/i18n';

const { Text, Paragraph } = Typography;

interface Step {
  key: string;
  titleKey: string;
  descKey: string;
  /** 页内可执行跳转；null 表示无需跳转（已自动完成） */
  to?: string;
  /** 检测此步是否已完成的钩子（基于 localStorage / 路由查询） */
  done: () => boolean;
}

const STEPS: Step[] = [
  {
    key: 'signup',
    titleKey: 'gs.step.signup',
    descKey: 'gs.step.signupDesc',
    to: '/signup',
    done: () => !!getAdminToken(),
  },
  {
    key: 'create-key',
    titleKey: 'gs.step.createKey',
    descKey: 'gs.step.createKeyDesc',
    to: '/settings',
    done: () => !!getApiKey(),
  },
  {
    key: 'try-chat',
    titleKey: 'gs.step.tryChat',
    descKey: 'gs.step.tryChatDesc',
    to: '/chat',
    done: () => false,
  },
  {
    key: 'audit',
    titleKey: 'gs.step.audit',
    descKey: 'gs.step.auditDesc',
    to: '/audit',
    done: () => false,
  },
  {
    key: 'sso',
    titleKey: 'gs.step.sso',
    descKey: 'gs.step.ssoDesc',
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
  const t = useT();

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Onboarding"
        title={t('gs.title')}
        sub={t('gs.subtitle')}
      />

      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Text strong>{(typeof window !== 'undefined' && window.localStorage?.getItem('agent-gateway.lang') === 'en' ? `${doneCount}/${STEPS.length} done` : `已完成 ${doneCount}/${STEPS.length} 步`)}</Text>
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
              <Tooltip title={finished ? t('gs.complete') : ''}>
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
                  <Text strong style={{ fontSize: 16 }}>{t(step.titleKey)}</Text>
                  {finished && <Tag color="success">{t('gs.complete')}</Tag>}
                </Space>
                <Paragraph type="secondary" style={{ margin: 0 }}>{t(step.descKey)}</Paragraph>
              </Space>
              {step.to && (
                <Link to={step.to}>
                  <Button type={finished ? 'default' : 'primary'} icon={<RocketOutlined />}>
                    {t('gs.action.go')}
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
        message={t('gs.refreshHint')}
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
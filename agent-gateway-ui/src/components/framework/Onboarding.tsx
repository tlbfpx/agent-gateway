/**
 * Onboarding — 首启 3 步引导
 *
 * 设计：底部居中卡片（不遮挡整个屏幕），可前进/后退/关闭
 *
 * 步骤（可由 useOnboarding 配置）：
 *   1. 签发 API Key（跳转到 /api-keys）
 *   2. 注册模型（跳转到 /models）
 *   3. 发送第一条消息（跳转到 /chat）
 */
import { useEffect } from 'react';
import { Button, Progress, Space, Tag } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, CloseOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useOnboarding, detectApiKey, detectModelsReady, type OnboardingStep } from '../../hooks/useOnboarding';

const DEFAULT_STEPS: OnboardingStep[] = [
  {
    key: 'api-key',
    title: '签发 API Key',
    description: '在「API Key」页面为你的租户签发第一个 Key，用于客户端调用网关。',
    to: '/api-keys',
    detect: async () => detectApiKey(),
  },
  {
    key: 'models',
    title: '注册模型',
    description: '在「模型管理」添加一个 LLM Provider（如 OpenAI、Claude、DeepSeek），网关将自动按权重路由。',
    to: '/models',
    detect: async () => detectModelsReady(),
  },
  {
    key: 'chat',
    title: '发送第一条消息',
    description: '在「对话测试」中选模型、发消息。流式逐字返回，支持工具调用可视化。',
    to: '/chat',
  },
];

interface OnboardingProps {
  steps?: OnboardingStep[];
}

export function Onboarding({ steps = DEFAULT_STEPS }: OnboardingProps) {
  const ob = useOnboarding(steps);
  const navigate = useNavigate();

  // mount 时启动自动检测
  useEffect(() => {
    ob.enableAutoDetect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!ob.isActive) return null;

  const total = steps.length;
  const done = ob.state.doneSteps.length;
  const pct = Math.round((done / total) * 100);
  const step = ob.currentStep;
  const idx = ob.state.current;

  return (
    <div
      style={{
        position: 'fixed',
        bottom: 24,
        right: 24,
        width: 360,
        maxWidth: 'calc(100vw - 32px)',
        background: 'color-mix(in srgb, var(--bg-surface) 88%, transparent)',
        backdropFilter: 'blur(10px)',
        border: '1px solid var(--border-default)',
        borderRadius: 'var(--r-lg)',
        boxShadow: '0 8px 24px rgba(15, 27, 61, 0.12)',
        padding: 16,
        zIndex: 1000,
      }}
      role="dialog"
      aria-label="首启引导"
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <ThunderboltOutlined style={{ color: 'var(--brand-amber)' }} />
        <span
          className="mono"
          style={{
            fontSize: 11,
            color: 'var(--brand-amber)',
            letterSpacing: 1.5,
            textTransform: 'uppercase',
          }}
        >
          引导 · {idx + 1} / {total}
        </span>
        <div style={{ flex: 1 }} />
        <Button
          type="text"
          size="small"
          icon={<CloseOutlined style={{ color: 'var(--text-2)' }} />}
          onClick={ob.skip}
          aria-label="跳过引导"
          style={{ border: '1px solid var(--border-default)', width: 24, height: 24, minWidth: 24, display: 'grid', placeItems: 'center', borderRadius: 4 }}
        />
      </div>

      <Progress
        percent={pct}
        showInfo={false}
        strokeColor="var(--brand-amber)"
        style={{ marginBottom: 12 }}
      />

      <div style={{ fontSize: 14, fontWeight: 500, marginBottom: 4, color: 'var(--text-1)' }}>
        {step.title}
        {ob.state.doneSteps.includes(idx) && (
          <Tag color="success" style={{ marginLeft: 8 }}>
            ✓ 已完成
          </Tag>
        )}
      </div>
      <div style={{ fontSize: 12, color: 'var(--text-3)', marginBottom: 12, lineHeight: 1.6 }}>
        {step.description}
      </div>

      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={ob.prev}
          disabled={idx === 0}
        >
          上一步
        </Button>
        <Space>
          <Button onClick={() => navigate(step.to)}>前往 {step.title}</Button>
          {idx === total - 1 ? (
            <Button
              style={{ background: 'var(--brand-amber)', borderColor: 'var(--brand-amber)', color: '#1A2237' }}
              icon={<ThunderboltOutlined />}
              onClick={ob.next}
            >
              完成
            </Button>
          ) : (
            <Button
              style={{ background: 'var(--brand-amber)', borderColor: 'var(--brand-amber)', color: '#1A2237' }}
              icon={<ArrowRightOutlined />}
              onClick={ob.next}
            >
              下一步
            </Button>
          )}
        </Space>
      </Space>
    </div>
  );
}

/** 在 Settings 中提供「重看引导」入口 */
export function RestartOnboardingButton() {
  const { restart } = useOnboarding(DEFAULT_STEPS);
  return (
    <Button onClick={restart} icon={<ThunderboltOutlined />}>
      重新播放引导
    </Button>
  );
}
/**
 * GrayscaleDialog — 灰度弹窗
 * - 调整权重 0-100，实时显示分流条
 * - 灰度组：相同 groupId 的一组模型按 weight 分流
 * - 计划切换：可选"到时间自动切到目标权重"
 * - 一键应用：调用 updateModel
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Modal,
  Slider,
  Input,
  Form,
  Switch,
  Button,
  Space,
  Tag,
  DatePicker,
  message,
  Empty,
  Alert,
} from 'antd';
import { ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { Model, GrayscaleMember } from '../../lib/api/models';
import { updateModel } from '../../lib/api/models';

/** 样本充足判定阈值：任一成员请求样本低于该值时不下结论 */
export const GRAY_MIN_SAMPLES = 30;

/**
 * 灰度对比报表结论生成（任务：运营评审 · 灰度报表结论建议）
 *
 * 规则：
 *  - 成员 < 2 → 无法对比
 *  - 任一启用成员请求样本 < 30 → "样本不足，建议延长观察"
 *  - 否则比较错误率最优 vs 最差成员（含倍数差与 P95 延迟对比），
 *    生成一句话建议（提高优选成员权重 / 全量切换 / 维持观察）
 */
export function buildGrayscaleConclusion(members: GrayscaleMember[]): string {
  if (members.length < 2) return '灰度组内可对比成员不足 2 个，暂无法给出切换建议。';
  const active = members.filter((m) => m.enabled);
  const pool = active.length >= 2 ? active : members;
  if (pool.some((m) => m.requests < GRAY_MIN_SAMPLES)) {
    return '样本不足（部分成员请求样本 < 30），建议延长观察后再做流量决策。';
  }
  const name = (m: GrayscaleMember) => m.displayName || m.modelId;
  const best = pool.reduce((a, b) => (b.errorRate < a.errorRate ? b : a));
  const worst = pool.reduce((a, b) => (b.errorRate > a.errorRate ? b : a));
  if (best === worst) {
    return `组内仅一个有效成员 ${name(best)}，建议扩充灰度组成员后再对比。`;
  }
  const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
  const diffTxt =
    best.errorRate > 0
      ? `（差 ${(worst.errorRate / best.errorRate).toFixed(1)}x）`
      : '（无错误）';
  const latencyTxt =
    best.p95LatencyMs < worst.p95LatencyMs
      ? `P95 延迟更低（${best.p95LatencyMs.toFixed(0)}ms vs ${worst.p95LatencyMs.toFixed(0)}ms）`
      : `但 P95 延迟更高（${best.p95LatencyMs.toFixed(0)}ms vs ${worst.p95LatencyMs.toFixed(0)}ms），建议小幅提权并继续观察`;
  const action =
    best.errorRate === 0 || worst.errorRate / Math.max(best.errorRate, 1e-9) >= 3
      ? '建议逐步提高权重或全量切换'
      : '建议小步提高权重并持续观察';
  return `成员 ${name(best)} 错误率 ${pct(best.errorRate)} vs ${name(worst)} ${pct(worst.errorRate)}${diffTxt}，${latencyTxt}，${action}。`;
}

/** 灰度对比报表底部结论组件 */
export function GrayscaleConclusion({ members }: { members: GrayscaleMember[] }) {
  const text = buildGrayscaleConclusion(members);
  const insufficient = members.length >= 2 && text.includes('样本不足');
  return (
    <Alert
      data-testid="grayscale-conclusion"
      type={insufficient ? 'warning' : members.length < 2 ? 'info' : 'success'}
      showIcon
      style={{ marginTop: 12 }}
      message="灰度结论建议"
      description={text}
    />
  );
}

interface GrayscaleDialogProps {
  open: boolean;
  onClose: () => void;
  model: Model;
  /** 同组其他模型（用于实时预览分流） */
  siblings: Model[];
  onApplied: () => void;
}

export function GrayscaleDialog({
  open,
  onClose,
  model,
  siblings,
  onApplied,
}: GrayscaleDialogProps) {
  const [weight, setWeight] = useState<number>(model.grayWeight ?? 0);
  const [enabled, setEnabled] = useState<boolean>(Boolean(model.grayWeight));
  const [scheduleOn, setScheduleOn] = useState(false);
  const [targetWeight, setTargetWeight] = useState<number>(100);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    if (open) {
      setWeight(model.grayWeight ?? 0);
      setEnabled(Boolean(model.grayWeight));
      setScheduleOn(false);
      setTargetWeight(100);
      form.resetFields();
    }
  }, [open, model, form]);

  // 实时分流预览：本模型权重 vs 同组其他模型
  const preview = useMemo(() => {
    const group = siblings.filter((m) => m.id !== model.id && m.grayGroup && m.grayGroup === model.grayGroup);
    const others = group.length > 0 ? group : siblings.filter((s) => s.id !== model.id);
    const total = weight + others.reduce((acc, m) => acc + (m.grayWeight ?? (m.enabled ? 100 : 0)), 0);
    const items = [
      {
        id: model.id,
        name: model.displayName || model.id,
        weight: weight,
        pct: total === 0 ? 0 : (weight / total) * 100,
        isSelf: true,
        enabled: true,
      },
      ...others.map((m) => ({
        id: m.id,
        name: m.displayName || m.id,
        weight: m.grayWeight ?? (m.enabled ? 100 : 0),
        pct: total === 0 ? 0 : ((m.grayWeight ?? (m.enabled ? 100 : 0)) / total) * 100,
        isSelf: false,
        enabled: m.enabled,
      })),
    ];
    return { items, total };
  }, [siblings, weight, model]);

  const onApply = async () => {
    try {
      setSubmitting(true);
      const body: Partial<Model> = {
        enabled: enabled || weight > 0,
        grayWeight: enabled ? weight : 0,
      };
      if (scheduleOn) {
        const v = await form.validateFields();
        if (v.switchAt) {
          body.graySchedule = {
            atWeight: targetWeight,
            atTime: (v.switchAt as any).toISOString?.() ?? v.switchAt,
          };
        }
      }
      await updateModel(model.id, body);
      message.success(`已应用灰度策略 · ${weight}%`);
      onApplied();
      onClose();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '应用失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={
        <Space>
          <ExperimentOutlined />
          <span>灰度策略 · {model.displayName || model.id}</span>
        </Space>
      }
      width={600}
      destroyOnHidden
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={submitting}
            onClick={onApply}
          >
            立即应用
          </Button>
        </Space>
      }
    >
      <Form layout="vertical" form={form}>
        {/* 启用开关 */}
        <Form.Item label="启用灰度">
          <Switch
            checked={enabled}
            onChange={setEnabled}
            checkedChildren="ON"
            unCheckedChildren="OFF"
          />
          <span style={{ marginLeft: 12, fontSize: 12, color: 'var(--text-3)' }}>
            关闭后该模型完全停用（路由 503）
          </span>
        </Form.Item>

        {/* 权重滑块 */}
        <Form.Item label={`权重 · ${weight}%`}>
          <Slider
            min={0}
            max={100}
            value={weight}
            onChange={setWeight}
            disabled={!enabled}
            marks={{ 0: '0%', 25: '25%', 50: '50%', 75: '75%', 100: '100%' }}
            tooltip={{ formatter: (v) => `${v}%` }}
          />
        </Form.Item>

        {/* 实时分流预览 */}
        <Form.Item label="实时分流预览">
          {preview.items.length === 1 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
                  同组内没有其他模型，100% 流量将路由到本模型
                </span>
              }
            />
          ) : (
            <div
              style={{
                display: 'flex',
                height: 36,
                borderRadius: 6,
                overflow: 'hidden',
                border: '1px solid var(--border-thin)',
              }}
            >
              {preview.items.map((p) => (
                <div
                  key={p.id}
                  style={{
                    width: `${p.pct}%`,
                    background: p.isSelf ? 'var(--brand-amber)' : 'var(--ant-primary)',
                    color: '#fff',
                    fontSize: 12,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    whiteSpace: 'nowrap',
                    opacity: p.enabled ? 1 : 0.4,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    padding: '0 8px',
                  }}
                  title={`${p.name} · ${p.weight}% (${p.pct.toFixed(1)}%)`}
                >
                  {p.name}
                </div>
              ))}
            </div>
          )}
          <div style={{ marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 12 }}>
            {preview.items.map((p) => (
              <div key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span
                  style={{
                    display: 'inline-block',
                    width: 10,
                    height: 10,
                    background: p.isSelf ? 'var(--brand-amber)' : 'var(--ant-primary)',
                    borderRadius: 2,
                    opacity: p.enabled ? 1 : 0.4,
                  }}
                />
                <span style={{ color: p.isSelf ? 'var(--text-1)' : 'var(--text-3)' }}>
                  {p.name} · {p.weight}% <Tag color={p.isSelf ? 'gold' : 'blue'}>
                    {p.pct.toFixed(1)}%
                  </Tag>
                </span>
              </div>
            ))}
          </div>
        </Form.Item>

        {/* 计划切换 */}
        <Form.Item label="计划切换">
          <Switch
            checked={scheduleOn}
            onChange={setScheduleOn}
            checkedChildren="ON"
            unCheckedChildren="OFF"
          />
          <span style={{ marginLeft: 12, fontSize: 12, color: 'var(--text-3)' }}>
            开启后到时间自动将权重切到目标值（适合"先小流量，稳定后切全量"）
          </span>
        </Form.Item>

        {scheduleOn && (
          <div
            style={{
              padding: 12,
              background: 'var(--bg-sunken)',
              border: '1px solid var(--border-thin)',
              borderRadius: 6,
              marginBottom: 16,
            }}
          >
            <Space wrap>
              <Form.Item
                label="切换时间"
                name="switchAt"
                rules={[{ required: scheduleOn, message: '请选择切换时间' }]}
                style={{ marginBottom: 0 }}
              >
                <DatePicker showTime placeholder="选择时间" />
              </Form.Item>
              <Form.Item label="目标权重" style={{ marginBottom: 0 }}>
                <Slider
                  style={{ width: 200 }}
                  min={0}
                  max={100}
                  value={targetWeight}
                  onChange={setTargetWeight}
                  marks={{ 0: '0%', 100: '100%' }}
                />
              </Form.Item>
            </Space>
          </div>
        )}

        {!model.grayGroup && (
          <div style={{ fontSize: 12, color: 'var(--text-3)', marginTop: 8 }}>
            <Tag color="default">提示</Tag>
            本模型未加入灰度组，所有流量将独立路由。建议在「编辑」中加入 grayGroup 字段以开启组内分流。
          </div>
        )}
      </Form>
    </Modal>
  );
}

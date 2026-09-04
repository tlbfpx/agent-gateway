import { Button, Card, Form, Input, Select, Space, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { guardrailsApi, type GuardrailMode, type GuardrailPolicy } from '../lib/api/guardrails';

const { Title, Paragraph } = Typography;

const MODE_OPTIONS: { label: string; value: GuardrailMode }[] = [
  { label: 'OBSERVE（仅记录）', value: 'OBSERVE' },
  { label: 'BLOCK（拒绝）', value: 'BLOCK' },
  { label: 'REDACT（脱敏）', value: 'REDACT' },
];

export function Guardrails() {
  const [policy, setPolicy] = useState<GuardrailPolicy | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    guardrailsApi.currentPolicy()
      .then(setPolicy)
      .catch((e: Error) => message.error(`加载策略失败:${e.message}`));
  }, []);

  const onSave = async () => {
    if (!policy) return;
    setLoading(true);
    try {
      const result = await guardrailsApi.updatePolicy(policy);
      message.success(`策略已更新(mode=${result.mode})`);
    } catch (e) {
      const err = e as Error;
      message.error(`更新失败:${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  if (!policy) return <div>加载中…</div>;

  return (
    <div style={{ padding: 24, maxWidth: 960 }}>
      <Title level={3}>Guardrails 安全策略</Title>
      <Paragraph type="secondary">
        输入侧检查 PII / Jailbreak / Toxicity,工具调用侧执行白/黑名单。
        模式切换:BLOCK=拒绝 / OBSERVE=仅记录 / REDACT=脱敏后继续。
      </Paragraph>

      <Card title="全局模式" style={{ marginBottom: 16 }}>
        <Form layout="vertical">
          <Form.Item label="模式">
            <Select<GuardrailMode>
              value={policy.mode}
              onChange={(mode) => setPolicy({ ...policy, mode })}
              options={MODE_OPTIONS}
            />
          </Form.Item>
        </Form>
      </Card>

      <Card title="输入检测规则" style={{ marginBottom: 16 }}>
        <Form layout="vertical">
          <Form.Item label="Toxicity 关键词（每行一条）">
            <Input.TextArea
              rows={4}
              value={policy.toxicityKeywords.join('\n')}
              onChange={(e) => setPolicy({ ...policy, toxicityKeywords: e.target.value.split('\n').filter(Boolean) })}
              placeholder="白痴&#10;idiot&#10;..."
            />
          </Form.Item>
          <Form.Item label="PII 正则（每行一条）">
            <Input.TextArea
              rows={4}
              value={policy.piiPatterns.join('\n')}
              onChange={(e) => setPolicy({ ...policy, piiPatterns: e.target.value.split('\n').filter(Boolean) })}
              placeholder="[A-Za-z0-9._%+-]+@...&#10;(?<![0-9])1[3-9][0-9]{9}(?![0-9])&#10;..."
            />
          </Form.Item>
          <Form.Item label="Jailbreak 模式（每行一条）">
            <Input.TextArea
              rows={4}
              value={policy.jailbreakPatterns.join('\n')}
              onChange={(e) => setPolicy({ ...policy, jailbreakPatterns: e.target.value.split('\n').filter(Boolean) })}
              placeholder="忽略之前的所有指令&#10;(?i)ignore previous instructions&#10;..."
            />
          </Form.Item>
        </Form>
      </Card>

      <Card title="工具策略" style={{ marginBottom: 16 }}>
        <Form layout="vertical">
          <Form.Item label="白名单（留空 = 不限制）">
            <Select
              mode="tags"
              value={policy.toolAllowList}
              onChange={(toolAllowList) => setPolicy({ ...policy, toolAllowList })}
              placeholder="safe_tool_a&#10;safe_tool_b"
            />
          </Form.Item>
          <Form.Item label="黑名单（优先级高于白名单）">
            <Select
              mode="tags"
              value={policy.toolBlockList}
              onChange={(toolBlockList) => setPolicy({ ...policy, toolBlockList })}
              placeholder="forbidden_tool"
            />
          </Form.Item>
        </Form>
      </Card>

      <Space>
        <Button type="primary" loading={loading} onClick={onSave}>
          保存策略（热生效）
        </Button>
        <Tag color="blue">改动立即生效,无需重启</Tag>
      </Space>
    </div>
  );
}
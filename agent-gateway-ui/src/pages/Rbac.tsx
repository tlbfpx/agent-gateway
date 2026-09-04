import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Empty, Form, Input, Row, Segmented, Select, Tag, Typography, message } from 'antd';
import { SafetyOutlined, SearchOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { previewRbac } from '../lib/api/rbac';
import type { RbacVerdict } from '../lib/api/rbac';
import { listAgents } from '../lib/api/agents';
import { listModels } from '../lib/api/models';

/** 表单形态：用户 + 资源类型 + 资源（下拉），映射到后端四要素 */
interface PreviewForm {
  actor: string;      // 用户 ID（内部标识，自由填写）
  resourceType: 'agent' | 'model';
  resource: string;   // agent 名或模型 id（下拉选择）
  action: string;     // 默认 chat:invoke
}

const ACTIONS = [
  { value: 'chat:invoke', label: '发起对话（chat:invoke）' },
  { value: 'agent:invoke', label: '调用 Agent（agent:invoke）' },
  { value: 'model:read', label: '查看模型（model:read）' },
];

/** 资源类型 → 后端 resource 路径（保持既有判定语义） */
const toResourcePath = (type: 'agent' | 'model', resource: string) =>
  type === 'agent' ? `agents/${resource}` : `models/${resource}`;

export function Rbac() {
  const [form] = Form.useForm<PreviewForm>();
  const [verdict, setVerdict] = useState<RbacVerdict | null>(null);
  const [loading, setLoading] = useState(false);
  const [agentNames, setAgentNames] = useState<string[]>([]);
  const [modelIds, setModelIds] = useState<string[]>([]);

  // 下拉数据源：Agent 注册表 + 模型列表（资源全部结构化选择）
  useEffect(() => {
    listAgents().then((ags) => setAgentNames(ags.map((a) => a.name))).catch(() => undefined);
    listModels().then((ms) => setModelIds(ms.map((m) => m.id))).catch(() => undefined);
  }, []);

  const resourceType = Form.useWatch('resourceType', form) ?? 'agent';
  const resourceOptions = useMemo(
    () => (resourceType === 'agent'
      ? agentNames.map((n) => ({ value: n, label: n }))
      : modelIds.map((id) => ({ value: id, label: id }))),
    [resourceType, agentNames, modelIds]);

  const onPreview = async () => {
    try {
      const v = await form.validateFields();
      setLoading(true);
      const res = await previewRbac({
        actor: v.actor,
        action: v.action,
        resource: toResourcePath(v.resourceType, v.resource),
      });
      setVerdict(res);
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '预览失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <PageHeader
        eyebrow="RBAC · 权限预览"
        title="权限自查"
        sub="模拟一次权限判定：选用户 → 选资源 → 看结果。不会发起真实调用，用于上线前自查与 403 排查。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={11}>
          <Card title="判定条件" extra={<SafetyOutlined style={{ color: 'var(--ant-primary)' }} />}>
            <Form form={form} layout="vertical"
              initialValues={{ resourceType: 'agent', action: 'chat:invoke' }}>
              <Form.Item label="用户 ID" name="actor" required
                tooltip="要模拟的用户（需已在「用户绑定」页绑定角色；如 alice / bob）"
                rules={[{ required: true, whitespace: true, message: '填写要查询的用户 ID' }]}>
                <Input placeholder="如 alice（见「用户绑定」页已绑定用户）" allowClear />
              </Form.Item>

              <Form.Item label="资源类型" name="resourceType" required>
                <Segmented block options={[
                  { value: 'agent', label: 'Agent' },
                  { value: 'model', label: '模型' },
                ]} onChange={() => form.setFieldValue('resource', undefined)} />
              </Form.Item>

              <Form.Item label="资源" name="resource" required
                rules={[{ required: true, message: '选择要判定的 Agent 或模型' }]}>
                <Select showSearch placeholder="下拉选择（可搜索）"
                  options={resourceOptions} optionFilterProp="label"
                  notFoundContent={resourceType === 'agent' ? 'Agent 注册表为空' : '模型列表为空'} />
              </Form.Item>

              <Form.Item label="操作" name="action" required tooltip="默认发起对话即可覆盖大多数场景">
                <Select options={ACTIONS} />
              </Form.Item>

              <Button type="primary" icon={<SearchOutlined />} onClick={onPreview}
                loading={loading} block size="large">
                开始判定
              </Button>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={13}>
          <Card title="判定结果">
            {!verdict ? (
              <Empty description="填好左侧条件后点「开始判定」，这里会显示允许 / 拒绝及原因" />
            ) : (
              <div
                style={{
                  padding: 32, borderRadius: 'var(--r-md)',
                  border: `1px solid ${verdict.allowed ? 'rgba(82,196,26,.35)' : 'rgba(255,77,79,.35)'}`,
                  background: verdict.allowed ? 'rgba(82,196,26,.05)' : 'rgba(255,77,79,.05)',
                  textAlign: 'center',
                }}
              >
                <div style={{ fontSize: 40, fontWeight: 600,
                  color: verdict.allowed ? 'var(--ant-success)' : 'var(--ant-error)' }}>
                  {verdict.allowed ? '✓ 允许' : '✗ 拒绝'}
                </div>
                {verdict.reason && (
                  <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
                    {verdict.reason}
                  </Typography.Text>
                )}
                {verdict.rule && (
                  <div style={{ marginTop: 8 }}>
                    <Tag color="blue">命中规则：{verdict.rule}</Tag>
                  </div>
                )}
                <Typography.Text type="secondary" style={{ display: 'block', marginTop: 16, fontSize: 12 }}>
                  {verdict.allowed
                    ? '该用户对所选资源有角色授权，真实请求会放行（仍受限流/配额约束）'
                    : '该用户未通过任何角色获得此资源授权，真实请求将被拒绝（403，写入审计）'}
                </Typography.Text>
              </div>
            )}
          </Card>
        </Col>
      </Row>
    </>
  );
}

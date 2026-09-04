/**
 * ScheduledReportDialog — 定时账单订阅（运营评审 #19）
 *
 * Modal 含 4 字段：
 *   - period  : 每日 / 每周 / 每月
 *   - range   : 24h / 7d / 30d（默认绑定当前报表 range）
 *   - dim     : 锁定为当前 Tab（disabled，但可读）
 *   - webhookUrl : 必填，URL 校验
 *
 * 提交 → POST /admin/reports/scheduled。
 * 下半区展示当前已订阅列表（GET /admin/reports/scheduled）+
 *   "测试一次" + "取消"两个动作。
 *
 * 风格与 PageHeader/Button 一致；复用 EmptyState、Tag、ErrorState。
 */
import { useEffect, useState } from 'react';
import {
  Modal,
  Form,
  Radio,
  Input,
  Button,
  Space,
  Tag,
  Table,
  Popconfirm,
  message,
  Tooltip,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  BellOutlined,
  DeleteOutlined,
  ThunderboltOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  createScheduledReport,
  listScheduledReports,
  cancelScheduledReport,
  testScheduledReport,
  type ScheduledReport,
  type ReportPeriod,
  type ReportRange,
} from '../../lib/api/usage';
import { EmptyState, ErrorState } from '../framework/EmptyState';

interface ScheduledReportDialogProps {
  open: boolean;
  onClose: () => void;
  /** 当前 CostCenter 选中的 range，订阅默认绑定 */
  currentRange: ReportRange;
  /** 当前 CostCenter 选中的 dim，订阅锁定（disable） */
  currentDim: ScheduledReport['dim'];
}

interface FormValues {
  period: ReportPeriod;
  range: ReportRange;
  webhookUrl: string;
}

const PERIOD_OPTIONS: { value: ReportPeriod; label: string }[] = [
  { value: 'daily', label: '每日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
];

const RANGE_OPTIONS: { value: ReportRange; label: string }[] = [
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
];

const DIM_LABEL: Record<ScheduledReport['dim'], string> = {
  tenant: '按租户',
  key: '按 API Key',
  model: '按模型',
  day: '按日期',
};

export function ScheduledReportDialog({
  open,
  onClose,
  currentRange,
  currentDim,
}: ScheduledReportDialogProps) {
  const [form] = Form.useForm<FormValues>();
  const [subs, setSubs] = useState<ScheduledReport[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const list = await listScheduledReports();
      setSubs(list);
    } catch (e: any) {
      setError(e?.message ?? '订阅列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      // 进入 dialog 时把 range 默认绑定到当前报表的 range
      form.setFieldsValue({ period: 'daily', range: currentRange });
      void load();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, currentRange]);

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      setSubmitting(true);
      await createScheduledReport({
        period: v.period,
        range: v.range,
        dim: currentDim,
        webhookUrl: v.webhookUrl,
      });
      message.success('订阅已创建');
      form.resetFields(['webhookUrl']);
      form.setFieldsValue({ period: 'daily', range: currentRange });
      await load();
    } catch (e: any) {
      if (e?.errorFields) return; // 表单校验已由 antd 提示
      message.error(e?.message ?? '订阅失败');
    } finally {
      setSubmitting(false);
    }
  };

  const onCancel = async (id: string) => {
    try {
      await cancelScheduledReport(id);
      message.success('已取消');
      await load();
    } catch (e: any) {
      message.error(e?.message ?? '取消失败');
    }
  };

  const onTest = async (id: string) => {
    setTestingId(id);
    try {
      const r = await testScheduledReport(id);
      if (r.ok) {
        message.success(`测试投递成功${r.latencyMs != null ? `（${r.latencyMs}ms）` : ''}`);
      } else {
        message.error(r.message ?? '测试投递失败');
      }
      await load();
    } catch (e: any) {
      message.error(e?.message ?? '测试失败');
    } finally {
      setTestingId(null);
    }
  };

  const columns: TableColumnsType<ScheduledReport> = [
    {
      title: '周期',
      dataIndex: 'period',
      width: 80,
      render: (v: ReportPeriod) => (
        <Tag color={v === 'daily' ? 'blue' : v === 'weekly' ? 'geekblue' : 'purple'}>
          {v === 'daily' ? '每日' : v === 'weekly' ? '每周' : '每月'}
        </Tag>
      ),
    },
    {
      title: '窗口',
      dataIndex: 'range',
      width: 70,
      render: (v: ReportRange) => <span className="mono">{v}</span>,
    },
    {
      title: '维度',
      dataIndex: 'dim',
      width: 110,
      render: (v: ScheduledReport['dim']) => <Tag>{DIM_LABEL[v]}</Tag>,
    },
    {
      title: '回调 URL',
      dataIndex: 'webhookUrl',
      render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
    },
    {
      title: '状态',
      width: 90,
      render: (_, r) =>
        r.enabled === false ? <Tag color="default">已停用</Tag> : <Tag color="success">● 活跃</Tag>,
    },
    {
      title: '操作',
      width: 170,
      align: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Tooltip title="立即测试一次投递">
            <Button
              type="link"
              size="small"
              icon={<ThunderboltOutlined />}
              loading={testingId === r.id}
              onClick={() => onTest(r.id)}
            >
              测试
            </Button>
          </Tooltip>
          <Popconfirm
            title="确定取消此订阅？"
            description="取消后将不再发送账单通知"
            onConfirm={() => onCancel(r.id)}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              取消
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={
        <Space>
          <BellOutlined />
          <span>定时账单订阅</span>
          <Tag color="blue">运营评审 #19</Tag>
        </Space>
      }
      width={720}
      footer={
        <Space>
          <Button onClick={onClose} disabled={submitting}>
            关闭
          </Button>
          <Button
            type="primary"
            loading={submitting}
            onClick={onSubmit}
            icon={<BellOutlined />}
          >
            创建订阅
          </Button>
        </Space>
      }
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ period: 'daily', range: currentRange }}
        style={{ marginTop: 8 }}
      >
        <Form.Item
          label="推送周期"
          name="period"
          rules={[{ required: true, message: '请选择推送周期' }]}
        >
          <Radio.Group options={PERIOD_OPTIONS} optionType="button" buttonStyle="solid" />
        </Form.Item>

        <Form.Item
          label="账单窗口"
          name="range"
          rules={[{ required: true, message: '请选择账单窗口' }]}
          extra="默认绑定当前报表窗口，可按需调整"
        >
          <Radio.Group options={RANGE_OPTIONS} optionType="button" buttonStyle="solid" />
        </Form.Item>

        <Form.Item label="账单维度" extra="锁定为当前 CostCenter Tab，避免跨维度混报">
          <Radio.Group value={currentDim} disabled>
            <Radio.Button value="tenant">按租户</Radio.Button>
            <Radio.Button value="key">按 API Key</Radio.Button>
            <Radio.Button value="model">按模型</Radio.Button>
            <Radio.Button value="day">按日期</Radio.Button>
          </Radio.Group>
          <Tag color="default" style={{ marginLeft: 12 }}>
            当前 {DIM_LABEL[currentDim]}
          </Tag>
        </Form.Item>

        <Form.Item
          label="回调 URL"
          name="webhookUrl"
          rules={[
            { required: true, message: '请输入回调 URL' },
            { type: 'url', message: '请输入合法的 http(s) URL' },
          ]}
        >
          <Input placeholder="https://your.svc/billing-hook" data-testid="scheduled-webhook-url" />
        </Form.Item>
      </Form>

      <div
        style={{
          marginTop: 8,
          paddingTop: 12,
          borderTop: '1px solid var(--border-thin)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <strong style={{ fontSize: 13 }}>已订阅</strong>
        <Tag>{subs.length}</Tag>
        <div style={{ flex: 1 }} />
        <Button
          size="small"
          type="text"
          icon={<ReloadOutlined />}
          onClick={load}
          loading={loading}
        >
          刷新
        </Button>
      </div>

      {error ? (
        <ErrorState error={error} onRetry={load} retryLabel="重新加载订阅" />
      ) : subs.length === 0 ? (
        <EmptyState
          variant="no-data"
          description="暂无订阅 · 填写上方表单即可按周期推送账单"
        />
      ) : (
        <Table<ScheduledReport>
          rowKey="id"
          columns={columns}
          dataSource={subs}
          loading={loading}
          pagination={false}
          size="small"
          data-testid="scheduled-list-table"
        />
      )}
    </Modal>
  );
}
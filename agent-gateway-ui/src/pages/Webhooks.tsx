import { useEffect, useState } from 'react';
import {
  Button,
  Table,
  Tag,
  Space,
  Popconfirm,
  Drawer,
  Form,
  Input,
  Select,
  Collapse,
  message,
  Tooltip,
  Modal,
  Alert,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  ReloadOutlined,
  NotificationOutlined,
  ThunderboltOutlined,
  ClockCircleOutlined,
  RedoOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import {
  listWebhooks,
  subscribeWebhook,
  unsubscribeWebhook,
  listDeadLetters,
  sendTestEvent,
  listWebhookHistory,
  redeliverDeadLetter,
} from '../lib/api/webhooks';
import type {
  WebhookSub,
  DeadLetter,
  WebhookHistoryRow,
  WebhookTestResult,
} from '../lib/api/webhooks';
import { EmptyState, ErrorState } from '../components/framework/EmptyState';
import { useAutoOpenCreate } from '../hooks/useAutoOpenCreate';

export function Webhooks() {
  const [subs, setSubs] = useState<WebhookSub[]>([]);
  const [dls, setDls] = useState<DeadLetter[]>([]);
  const [history, setHistory] = useState<WebhookHistoryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [testModal, setTestModal] = useState<{ sub: WebhookSub; event: string } | null>(null);
  const [testResult, setTestResult] = useState<WebhookTestResult | null>(null);
  const [testing, setTesting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm();

  const [error, setError] = useState<string>('');
  const [redelivering, setRedelivering] = useState<string | null>(null);
  const [redeliverError, setRedeliverError] = useState<string>('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [s, d, h] = await Promise.allSettled([
        listWebhooks(),
        listDeadLetters(),
        listWebhookHistory('24h'),
      ]);
      if (s.status === 'fulfilled') setSubs(s.value);
      else setError('订阅列表加载失败');
      if (d.status === 'fulfilled') setDls(d.value);
      if (h.status === 'fulfilled') setHistory(h.value);
    } catch (e: any) {
      setError(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  // ⌘K Quick Action: /webhooks?action=create 自动打开订阅抽屉
  useAutoOpenCreate(() => setDrawerOpen(true));

  const onCreate = async () => {
    try {
      const v = await form.validateFields();
      setCreating(true);
      await subscribeWebhook(v.url, v.secret ?? '', v.events);
      message.success('已订阅');
      setDrawerOpen(false);
      form.resetFields();
      load();
    } catch (e: any) {
      if (e?.errorFields) return; // 表单校验失败已由 antd 内联提示
      message.error(e?.message ?? '订阅失败');
    } finally {
      setCreating(false);
    }
  };

  const onDelete = async (url: string) => {
    try {
      await unsubscribeWebhook(url);
      message.success('已取消');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '取消失败');
    }
  };

  const openTest = (sub: WebhookSub) => {
    setTestModal({ sub, event: sub.events[0] ?? 'chat.completed' });
    setTestResult(null);
  };

  const onSendTest = async () => {
    if (!testModal) return;
    setTesting(true);
    setTestResult(null);
    try {
      const r = await sendTestEvent(testModal.sub.url, testModal.event);
      setTestResult(r);
    } catch (e: any) {
      setTestResult({ ok: false, latencyMs: 0, response: e?.message ?? '请求失败' });
    } finally {
      setTesting(false);
    }
  };

  // 运营评审 #12：死信重新投递
  const onRedeliver = async (url: string, event: string) => {
    const key = `${url}::${event}`;
    setRedelivering(key);
    setRedeliverError('');
    try {
      const r = await redeliverDeadLetter(url, event);
      if (r.ok) {
        message.success(`已重新投递 ${event} → ${url}（尝试 ${r.attempts}）`);
        // 从本地列表中移除该项（避免等待下一次轮询）
        setDls((cur) =>
          cur.filter((d) => !(d.url === url && d.event === event)),
        );
      } else {
        setRedeliverError(r.error ?? '重新投递失败');
      }
    } catch (e: any) {
      setRedeliverError(e?.message ?? '重新投递失败');
    } finally {
      setRedelivering(null);
    }
  };

  const cols: TableColumnsType<WebhookSub> = [
    { title: 'URL', dataIndex: 'url', render: (v) => <span className="mono">{v}</span> },
    {
      title: '事件',
      dataIndex: 'events',
      render: (es: string[]) => (
        <>
          {es.map((e) => (
            <Tag key={e} color="blue">{e}</Tag>
          ))}
        </>
      ),
    },
    { title: '状态', width: 100, render: () => <Tag color="success">● 活跃</Tag> },
    {
      title: '操作',
      width: 200,
      align: 'right',
      render: (_, s) => (
        <Space size={4}>
          <Tooltip title="发送测试事件">
            <Button
              type="link"
              size="small"
              icon={<ThunderboltOutlined />}
              onClick={() => openTest(s)}
            >
              测试
            </Button>
          </Tooltip>
          <Popconfirm title="确认取消订阅？" onConfirm={() => onDelete(s.url)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              取消
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const dlCols: TableColumnsType<DeadLetter> = [
    { title: 'URL', dataIndex: 'url', render: (v) => <span className="mono">{v}</span> },
    { title: '事件', dataIndex: 'event', render: (v) => <Tag>{v}</Tag> },
    { title: '尝试次数', dataIndex: 'attempts', width: 100 },
    { title: '错误', dataIndex: 'error', render: (v) => <span style={{ color: 'var(--ant-error)' }}>{v}</span> },
    {
      title: '操作',
      width: 130,
      align: 'right',
      render: (_, r) => {
        const key = `${r.url}::${r.event}`;
        return (
          <Popconfirm
            title="确定重新投递此死信事件？"
            description="将以新一次尝试发送，成功后将自动从死信队列移除"
            okText="重新投递"
            cancelText="取消"
            onConfirm={() => onRedeliver(r.url, r.event)}
          >
            <Button
              type="link"
              size="small"
              icon={<RedoOutlined />}
              loading={redelivering === key}
              data-testid="dlq-redeliver"
            >
              重新投递
            </Button>
          </Popconfirm>
        );
      },
    },
  ];

  const historyCols: TableColumnsType<WebhookHistoryRow> = [
    { title: '时间', dataIndex: 'time', width: 170, render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v?.slice(0, 19)}</span> },
    { title: 'URL', dataIndex: 'url', render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    { title: '事件', dataIndex: 'event', width: 180, render: (v) => <Tag color="blue">{v}</Tag> },
    {
      title: '状态',
      width: 100,
      render: (_, r) => (
        <Tag color={r.status === 'success' ? 'success' : 'error'}>
          {r.status === 'success' ? '✓ 成功' : '✗ 失败'}
        </Tag>
      ),
    },
    {
      title: '尝试',
      dataIndex: 'attempts',
      width: 80,
      render: (v) => <span className="mono">{v}</span>,
    },
    {
      title: '延迟',
      dataIndex: 'latencyMs',
      width: 100,
      render: (v) => (
        <Space size={4}>
          <ClockCircleOutlined style={{ fontSize: 10, color: 'var(--text-3)' }} />
          <span className="mono">{v}ms</span>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Webhooks · 回调"
        title="Webhook 订阅与死信"
        sub={`共 ${subs.length} 个订阅 · ${dls.length} 条死信`}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setDrawerOpen(true)}>
              新增订阅
            </Button>
          </>
        }
      />

      <div className="content-card">
        <div className="content-card-head">
          <div className="content-card-title">活跃订阅</div>
        </div>
        {error ? (
          <ErrorState error={error} onRetry={load} />
        ) : subs.length === 0 ? (
          <EmptyState
            variant="no-data"
            description="尚无 Webhook 订阅"
            icon={<NotificationOutlined />}
            action={{ label: '+ 添加第一个订阅', onClick: () => setDrawerOpen(true) }}
          />
        ) : (
          <Table
            rowKey="url"
            columns={cols}
            dataSource={subs}
            loading={loading}
            pagination={false}
          />
        )}
      </div>

      <Collapse
        ghost
        style={{ marginTop: 8 }}
        items={[
          {
            key: 'dlq',
            label: (
              <Space>
                <span style={{ fontWeight: 600, fontSize: 14 }}>死信队列</span>
                <Tag color={dls.length > 0 ? 'error' : 'default'}>{dls.length}</Tag>
              </Space>
            ),
            children: (
              <>
                {redeliverError && (
                  <div style={{ marginBottom: 12 }}>
                    <ErrorState
                      error={redeliverError}
                      onRetry={() => setRedeliverError('')}
                      retryLabel="知道了"
                    />
                  </div>
                )}
                {dls.length === 0 ? (
                  <EmptyState variant="no-data" description="死信队列为空 · 所有投递均成功" />
                ) : (
                  <Table
                    rowKey={(r) => `${r.url}-${r.event}-${r.lastTryAt}`}
                    columns={dlCols}
                    dataSource={dls}
                    loading={loading}
                    pagination={false}
                  />
                )}
              </>
            ),
          },
          {
            key: 'history',
            label: (
              <Space>
                <span style={{ fontWeight: 600, fontSize: 14 }}>投递历史</span>
                <Tag color={history.length > 0 ? 'blue' : 'default'}>{history.length}</Tag>
              </Space>
            ),
            children: history.length === 0 ? (
              <EmptyState variant="no-data" description="近 24h 暂无投递记录" />
            ) : (
              <Table
                rowKey="id"
                columns={historyCols}
                dataSource={history}
                loading={loading}
                pagination={{ pageSize: 10 }}
              />
            ),
          },
        ]}
      />

      <Drawer
        title="新增 Webhook 订阅"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={460}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)} disabled={creating}>
              取消
            </Button>
            <Button type="primary" onClick={onCreate} loading={creating}>
              订阅
            </Button>
          </Space>
        }
      >
        <Form layout="vertical" form={form}>
          <Form.Item label="回调 URL" name="url" rules={[{ required: true, type: 'url' }]}>
            <Input placeholder="https://your.svc/hook" />
          </Form.Item>
          <Form.Item label="签名密钥" name="secret">
            <Input.Password placeholder="用于 HMAC-SHA256 验签" />
          </Form.Item>
          <Form.Item label="订阅事件" name="events" rules={[{ required: true }]}>
            <Select
              mode="multiple"
              options={[
                { value: 'chat.completed', label: 'chat.completed' },
                { value: 'chat.failed', label: 'chat.failed' },
                { value: 'model.registered', label: 'model.registered' },
                { value: 'config.rolledback', label: 'config.rolledback' },
                { value: 'audit.critical', label: 'audit.critical' },
              ]}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
}
/**
 * AlertCenter — 告警中心(spec 2026-08-19 §6.3)
 *
 * 页签:告警流(firing 优先、dedup 折叠计数、状态流转、认领人)+ 规则管理(CRUD)。
 * 数据来自 PG 持久化(AlertEngine 定时求值);未配置存储(503)→ 引导页。
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Button,
  Tag,
  Space,
  Drawer,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Popconfirm,
  Tabs,
  Tooltip,
  message,
  Alert,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ReloadOutlined,
  AlertOutlined,
  CheckCircleOutlined,
  UserOutlined,
  BellOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import {
  alertsApi,
  OPERATOR_LABEL,
  SEVERITY_COLOR,
  METRIC_OPTIONS,
  type AlertRule,
  type AlertRuleInput,
  type AlertRecord,
} from '../lib/api/alerts';
import { ErrorState, EmptyState } from '../components/framework/EmptyState';
import { useAutoOpenCreate } from '../hooks/useAutoOpenCreate';

const SEVERITY_OPTIONS = [
  { value: 'info', label: 'ℹ Info' },
  { value: 'warning', label: '⚠ Warning' },
  { value: 'critical', label: '⛔ Critical' },
];

const OPERATOR_OPTIONS = (Object.keys(OPERATOR_LABEL) as (keyof typeof OPERATOR_LABEL)[]).map((op) => ({
  value: op,
  label: `实际值 ${OPERATOR_LABEL[op]} 阈值`,
}));

function fmtTime(iso: string): string {
  return iso?.slice(0, 19).replace('T', ' ');
}

export function AlertCenter() {
  // 成本中心/预算页下钻联动：读取 ?q= 关键字自动过滤告警流
  const [searchParams, setSearchParams] = useSearchParams();
  const q = searchParams.get('q') ?? '';
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [records, setRecords] = useState<AlertRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [storageHint, setStorageHint] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<AlertRule | null>(null);
  const [tab, setTab] = useState<'firing' | 'all' | 'rules'>('firing');
  const [form] = Form.useForm<AlertRuleInput>();

  const load = async (opts?: { silent?: boolean }) => {
    const silent = opts?.silent ?? false;
    setLoading(true);
    setError('');
    try {
      const [r, a] = await Promise.all([
        alertsApi.rules.list(),
        alertsApi.records(),
      ]);
      setRules(r);
      setRecords(a);
      setStorageHint(false);
    } catch (err: any) {
      // 轮询静默失败：不打断现有数据，也不弹错误态
      if (silent) return;
      const msg = err?.message ?? '加载失败';
      if (msg.includes('503') || msg.includes('持久化存储')) {
        setStorageHint(true);
      } else {
        setError(msg);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  // 30s 自动轮询：仅页面可见时执行；失败静默，保留现有数据
  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') {
        load({ silent: true });
      }
    }, 30_000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // URL 参数 ?q= 过滤：命中 ruleId / dedupKey / note / severity / 状态
  const visibleRecords = useMemo(() => {
    if (!q) return records;
    const k = q.toLowerCase();
    return records.filter((a) =>
      [a.ruleId, a.dedupKey, a.note ?? '', a.severity, a.state]
        .some((v) => String(v ?? '').toLowerCase().includes(k)),
    );
  }, [records, q]);

  const firingCount = visibleRecords.filter((a) => a.state === 'firing').length;
  const unclaimedCritical = records.filter(
    (a) => a.state === 'firing' && a.severity === 'critical' && !a.claimedBy,
  ).length;

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      metricName: 'chat.errors',
      operator: 'GT',
      threshold: 10,
      windowSeconds: 300,
      silenceMinutes: 30,
      severity: 'warning',
      enabled: true,
    } as AlertRuleInput);
    setDrawerOpen(true);
  };

  useAutoOpenCreate(openCreate);

  const openEdit = (r: AlertRule) => {
    setEditing(r);
    form.setFieldsValue(r);
    setDrawerOpen(true);
  };

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      if (editing) {
        await alertsApi.rules.update(editing.id, v);
        message.success('已更新');
      } else {
        await alertsApi.rules.create(v);
        message.success('已创建');
      }
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '操作失败');
    }
  };

  const onDelete = async (id: string) => {
    await alertsApi.rules.remove(id);
    message.success('已删除');
    load();
  };

  const onAck = async (id: string) => {
    await alertsApi.ack(id, 'admin');
    message.success('已认领');
    load();
  };

  const onSilence = async (id: string) => {
    await alertsApi.silence(id, '手动静默');
    message.success('已静默(引擎侧按静默窗口去重)');
    load();
  };

  if (storageHint) {
    return (
      <>
        <PageHeader eyebrow="Alerts · 告警" title="告警中心" />
        <Alert
          type="info"
          showIcon
          message="未配置持久化存储"
          description={
            <>告警规则与记录需要 PostgreSQL + TimescaleDB。请启动{' '}
              <code>docker compose -f docker-compose.observability.yml up -d</code>{' '}
              并在网关配置 <code>observability.storage.jdbc-url</code> 后重启。
            </>
          }
        />
      </>
    );
  }

  const shown = tab === 'firing' ? visibleRecords.filter((a) => a.state === 'firing') : visibleRecords;

  return (
    <>
      <PageHeader
        eyebrow="Alerts · 告警"
        title="告警中心"
        sub={
          <Space>
            <span>{rules.length} 条规则</span>
            <Tag color={firingCount > 0 ? 'error' : 'success'}>{firingCount} firing</Tag>
            {unclaimedCritical > 0 && <Tag color="error">{unclaimedCritical} 严重未认领</Tag>}
            <Tag data-testid="auto-refresh-hint">每 30 秒自动刷新</Tag>
          </Space>
        }
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建规则
            </Button>
          </Space>
        }
      />

      {error && <ErrorState error={error} onRetry={load} />}

      {!error && (
        <>
          <Tabs
            activeKey={tab}
            onChange={(k) => setTab(k as typeof tab)}
            items={[
              { key: 'firing', label: `触发中 · ${firingCount}` },
              { key: 'all', label: `全部 · ${visibleRecords.length}` },
              { key: 'rules', label: `规则 · ${rules.length}` },
            ]}
          />

          {tab !== 'rules' && (
            <>
              {q && (
                <div
                  data-testid="alerts-drilldown-filter"
                  style={{ marginBottom: 12, fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}
                >
                  <Tag color="gold">下钻过滤</Tag>
                  <span>
                    已按 <strong className="mono">{q}</strong> 过滤告警流（{visibleRecords.length} / {records.length} 条）
                  </span>
                  <a onClick={() => setSearchParams({})}>清除过滤</a>
                </div>
              )}
              {shown.length === 0 ? (
                <EmptyState variant="no-data" description={tab === 'firing' ? '无触发中告警' : '暂无告警记录'} icon={<CheckCircleOutlined />} />
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {shown.map((a) => (
                    <AlertRecordRow
                      key={a.id}
                      record={a}
                      onAck={() => onAck(a.id)}
                      onSilence={() => onSilence(a.id)}
                    />
                  ))}
                </div>
              )}
            </>
          )}

          {tab === 'rules' && (
            <>
              {rules.length === 0 ? (
                <EmptyState variant="no-data" description="尚无告警规则" icon={<AlertOutlined />} action={{ label: '+ 创建第一条规则', onClick: openCreate }} />
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {rules.map((r) => (
                    <RuleRow key={r.id} rule={r} onEdit={() => openEdit(r)} onDelete={() => onDelete(r.id)} />
                  ))}
                </div>
              )}
            </>
          )}
        </>
      )}

      <Drawer
        title={editing ? `编辑规则 · ${editing.name}` : '新建告警规则'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={520}
        destroyOnHidden
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={onSubmit}>
              保存
            </Button>
          </Space>
        }
      >
        <Form<AlertRuleInput> layout="vertical" form={form}>
          <Form.Item label="规则名称" name="name" rules={[{ required: true }]}>
            <Input placeholder="如 网关错误数超 10" />
          </Form.Item>
          <Form.Item label="监控指标" name="metricName" rules={[{ required: true }]}>
            <Select options={METRIC_OPTIONS} showSearch />
          </Form.Item>
          <Space wrap>
            <Form.Item label="条件" name="operator" rules={[{ required: true }]} style={{ width: 140 }}>
              <Select options={OPERATOR_OPTIONS} />
            </Form.Item>
            <Form.Item label="阈值" name="threshold" rules={[{ required: true }]} style={{ width: 140 }}>
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Space wrap>
            <Form.Item label="聚合窗口(秒)" name="windowSeconds" style={{ width: 140 }}>
              <InputNumber min={30} max={86400} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="静默窗口(分钟)" name="silenceMinutes" style={{ width: 140 }}>
              <InputNumber min={1} max={1440} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item label="严重级别" name="severity" rules={[{ required: true }]}>
            <Select options={SEVERITY_OPTIONS} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch checkedChildren="ON" unCheckedChildren="OFF" />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
}

function AlertRecordRow({
  record: r,
  onAck,
  onSilence,
}: {
  record: AlertRecord;
  onAck: () => void;
  onSilence: () => void;
}) {
  const firing = r.state === 'firing';
  return (
    <div
      style={{
        padding: 12,
        background: firing ? 'rgba(255, 77, 79, 0.04)' : 'var(--bg-sunken)',
        border: `1px solid ${firing ? 'rgba(255, 77, 79, 0.3)' : 'var(--border-thin)'}`,
        borderRadius: 6,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <Tag color={SEVERITY_COLOR[r.severity]}>{r.severity.toUpperCase()}</Tag>
        <Tag color={firing ? 'error' : 'success'}>{firing ? 'FIRING' : 'RESOLVED'}</Tag>
        <strong>{r.labels?.rule ?? r.ruleId}</strong>
        <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)' }}>{r.dedupKey}</span>
        <div style={{ flex: 1 }} />
        <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)' }}>
          {fmtTime(r.recentlyTriggeredAt)}
        </span>
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-2)', marginBottom: 8 }}>
        <Tooltip title="观测值 / 阈值">
          <span className="mono">
            {r.observedValue?.toFixed(2)} / {r.threshold}
          </span>
        </Tooltip>
        <span style={{ marginLeft: 12 }}>触发 {r.triggerCount} 次(自 {fmtTime(r.firstFiredAt)})</span>
        {r.claimedBy && (
          <Tag style={{ marginLeft: 12 }} icon={<UserOutlined />}>{r.claimedBy}</Tag>
        )}
        {r.note && <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--text-3)' }}>{r.note}</span>}
      </div>
      {firing && (
        <Space>
          <Button size="small" type="primary" icon={<CheckCircleOutlined />} onClick={onAck}>
            认领
          </Button>
          <Button size="small" icon={<BellOutlined />} onClick={onSilence}>
            静默
          </Button>
        </Space>
      )}
    </div>
  );
}

function RuleRow({ rule: r, onEdit, onDelete }: { rule: AlertRule; onEdit: () => void; onDelete: () => void }) {
  return (
    <div
      style={{
        padding: 12,
        background: 'var(--bg-surface)',
        border: '1px solid var(--border-thin)',
        borderRadius: 6,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <Tag color={SEVERITY_COLOR[r.severity]}>{r.severity}</Tag>
        <strong>{r.name}</strong>
        <Tag color={r.enabled ? 'success' : 'default'}>{r.enabled ? 'ON' : 'OFF'}</Tag>
        <div style={{ flex: 1 }} />
        <Button type="link" size="small" icon={<EditOutlined />} onClick={onEdit}>
          编辑
        </Button>
        <Popconfirm title="删除规则？" onConfirm={onDelete}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      </div>
      <div style={{ fontSize: 12, color: 'var(--text-3)' }}>
        <Space wrap>
          <span>指标：<strong className="mono">{r.metricName}</strong></span>
          <span>条件：<strong>{OPERATOR_LABEL[r.operator]} {r.threshold}</strong></span>
          <span>窗口：<strong>{r.windowSeconds}s</strong></span>
          <span>静默：<strong>{r.silenceMinutes}min</strong></span>
        </Space>
      </div>
    </div>
  );
}

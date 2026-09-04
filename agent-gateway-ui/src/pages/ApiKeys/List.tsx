import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Row,
  Col,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Table,
  Tag,
  Popconfirm,
  Space,
  Empty,
  Alert,
  message,
  Tooltip,
  Popover,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  PlusOutlined,
  CopyOutlined,
  LockOutlined,
  DollarOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../../components/framework/PageHeader';
import { listApiKeys, createApiKey, deleteApiKey, topUpVirtualKey } from '../../lib/api/keys';
import type { ApiKey } from '../../lib/api/keys';
import { maskKey, relTime } from '../../lib/format';
import { previewRbac } from '../../lib/api/rbac';
import { usePermission } from '../../hooks/useRole';
import { ErrorState } from '../../components/framework/EmptyState';
import { broadcastAcrossTabs, useEmit } from '../../hooks/useEventBus';
import { CodeSnippet } from '../../components/CodeSnippet';
import type { CodegenRequest } from '../../lib/codegen';

const STATUS_LABEL: Record<string, { color: string; text: string }> = {
  true: { color: 'success', text: '● 启用' },
  false: { color: 'error', text: '● 已撤销' },
};

export function ApiKeysList() {
  const navigate = useNavigate();
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const canCreate = usePermission('apikey.create');
  const canRevoke = usePermission('apikey.revoke');
  const [loading, setLoading] = useState(false);
  const [issuedValue, setIssuedValue] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [rbacVerdict, setRbacVerdict] = useState<{ allowed: boolean; reason?: string } | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [expiryFilter, setExpiryFilter] = useState<'all' | 'expiring' | 'expired'>('all');
  const [error, setError] = useState('');
  const [issuing, setIssuing] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setKeys(await listApiKeys());
      setError('');
    } catch (e: any) {
      setKeys([]);
      setError(e?.message ?? 'API Key 列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // demo rbac preview on first load
    (async () => {
      setPreviewing(true);
      try {
        const v = await previewRbac({
          actor: 'admin@primary',
          action: 'chat:invoke',
          resource: 'tenant-b/models/claude-3.7',
        });
        setRbacVerdict(v);
      } catch {
        // mock fallback
        setRbacVerdict({ allowed: false, reason: 'cross-tenant 隔离策略拒绝' });
      } finally {
        setPreviewing(false);
      }
    })();
  }, []);

  const onIssue = async () => {
    try {
      const v = await form.validateFields();
      setIssuing(true);
      const result = await createApiKey({
        owner: v.owner ?? 'admin',
        tenant: v.tenant,
        // 多选 Select 本身返回 string[]，直接透传（旧代码误包了一层数组）
        models: v.models?.length ? v.models : undefined,
        rateLimitRpm: v.rateLimitRpm ? Number(v.rateLimitRpm) : undefined,
        expiresAt: v.expiresAt || undefined,
      });
      const created = result.created ?? result.apiKey;
      const value = created?.value ?? '';
      setIssuedValue(value);
      message.success('已签发');
      form.resetFields();
      broadcastAcrossTabs('apikeys:changed', undefined);
      load();
    } catch (e: any) {
      if (e?.errorFields) return; // 表单校验失败已由 antd 内联提示
      message.error(e?.message ?? '签发失败');
    } finally {
      setIssuing(false);
    }
  };

  const onRevoke = async (id: string) => {
    try {
      await deleteApiKey(id);
      message.success('已撤销');
      broadcastAcrossTabs('apikeys:changed', undefined);
      load();
    } catch (e: any) {
      message.error(e?.message ?? '撤销失败');
    }
  };

  /**
   * Round6：Top up 充值流程
   * 1. 弹出 Modal 输入金额（默认 100）
   * 2. 调 topUpVirtualKey → 返回 checkoutUrl
   * 3. Modal.info 展示 URL + CopyOutlined 一键复制
   * 4. 关闭后 load() 刷新余额
   */
  const onTopUp = (k: ApiKey) => {
    let amount = 100;
    Modal.confirm({
      title: `为 ${maskKey(k.id, 'pk_live_')} 充值`,
      icon: <DollarOutlined />,
      content: (
        <div>
          <div style={{ marginBottom: 8, color: 'var(--text-3)', fontSize: 13 }}>
            选择充值金额（CNY），将通过 Stripe Checkout 完成支付
          </div>
          <InputNumber
            min={10 as number}
            max={100000 as number}
            defaultValue={100 as number}
            step={50 as number}
            style={{ width: '100%' }}
            data-testid="topup-amount-input"
            onChange={(v) => {
              amount = Number(v) || 100;
            }}
            formatter={(v) => `¥ ${v ?? ''}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={(v) => Number((v ?? '').toString().replace(/[^\d.]/g, '')) || 0}
          />
          <div style={{ marginTop: 8, fontSize: 12, color: 'var(--text-4)' }}>
            当前余额 ¥{(k.balanceCny ?? 0).toFixed(2)} · 租户 {k.tenant}
          </div>
        </div>
      ),
      okText: '确认充值',
      cancelText: '取消',
      onOk: async () => {
        try {
          const result = await topUpVirtualKey(k.id, amount);
          Modal.info({
            title: '充值会话已创建',
            icon: <DollarOutlined style={{ color: 'var(--brand-amber)' }} />,
            content: (
              <div>
                <div style={{ marginBottom: 8 }}>
                  金额：<strong>¥{result.amountCny.toFixed(2)}</strong>
                </div>
                <div style={{ marginBottom: 4, color: 'var(--text-3)', fontSize: 12 }}>
                  跳转地址（点击复制或在外部打开）：
                </div>
                <div
                  style={{
                    padding: 8,
                    background: 'var(--bg-sunken)',
                    borderRadius: 4,
                    fontFamily: 'var(--font-mono)',
                    fontSize: 12,
                    wordBreak: 'break-all',
                  }}
                >
                  {result.checkoutUrl}
                </div>
                <Button
                  type="link"
                  icon={<CopyOutlined />}
                  data-testid="copy-checkout-url"
                  onClick={() => {
                    navigator.clipboard.writeText(result.checkoutUrl);
                    message.success('已复制');
                  }}
                >
                  复制 checkout URL
                </Button>
                <div style={{ marginTop: 8, fontSize: 12, color: 'var(--text-4)' }}>
                  会话 ID：<code className="mono">{result.sessionId}</code>
                </div>
              </div>
            ),
            okText: '完成',
            onOk: () => load(),
            onCancel: () => load(),
          });
          return Promise.resolve();
        } catch (e: any) {
          message.error(e?.message ?? '充值失败');
          return Promise.reject(e);
        }
      },
    });
  };

  const columns: TableColumnsType<ApiKey> = [
    {
      title: 'Key 预览',
      dataIndex: 'id',
      render: (id, k) => <span className="mono">{maskKey(k.id ?? id, k.id?.startsWith('pk_test_') ? 'pk_test_' : 'pk_live_')}</span>,
    },
    { title: '租户', dataIndex: 'tenant', render: (v) => <Tag color="blue">{v}</Tag> },
    {
      title: '余额 (¥)',
      dataIndex: 'balanceCny',
      width: 110,
      align: 'right',
      sorter: (a, b) => (a.balanceCny ?? 0) - (b.balanceCny ?? 0),
      render: (v?: number) =>
        v === undefined || v === null ? (
          <span style={{ color: 'var(--text-4)' }}>—</span>
        ) : (
          <Tooltip title={`当月配额 ¥${((v ?? 0)).toFixed(2)} / 月`}>
            <strong style={{ color: v > 0 ? 'var(--brand-amber)' : 'var(--text-4)' }}>
              ¥{v.toFixed(2)}
            </strong>
          </Tooltip>
        ),
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      width: 170,
      render: (v?: string) => {
        if (!v) return <span style={{ color: 'var(--text-3)' }}>永久有效</span>;
        const remainMs = new Date(v).getTime() - Date.now();
        if (Number.isNaN(remainMs)) return <span className="mono" style={{ fontSize: 12 }}>{v}</span>;
        if (remainMs <= 0) return <Tag color="error">已过期</Tag>;
        const days = Math.floor(remainMs / 86_400_000);
        if (days <= 7) {
          return (
            <Tooltip title={`${v} 到期`}>
              <Tag color="warning">剩余 {days} 天</Tag>
            </Tooltip>
          );
        }
        return (
          <Tooltip title={v}>
            <span className="mono" style={{ fontSize: 12 }}>{v.slice(0, 10)}</span>
          </Tooltip>
        );
      },
    },
    {
      title: '最近使用',
      dataIndex: 'lastUsedAt',
      width: 110,
      render: (v?: string) =>
        v ? <span style={{ fontSize: 12, color: 'var(--text-3)' }}>{relTime(v)}</span> : <span style={{ color: 'var(--text-4)' }}>从未</span>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      render: (v: boolean) => {
        const cfg = STATUS_LABEL[String(v)];
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '操作',
      width: 260,
      align: 'right',
      render: (_, k) => {
        // 给每个 key 构造 chat/completions 示例请求（base 取自 window.location.origin）
        // 注意：render 是非 hook 函数，不能在这里调 useMemo；origin 是不变的，k.value 通过
        // onChange 重渲染整列时会拿到新值。
        const origin =
          typeof window !== 'undefined' && window.location?.origin
            ? window.location.origin
            : '';
        const sampleReq: CodegenRequest = {
          method: 'POST',
          url: `${origin}/v1/chat/completions`,
          apiKey: k.id,
          headers: { 'Content-Type': 'application/json' },
          body: {
            model: 'gpt-4o',
            messages: [{ role: 'user', content: '你好，请自我介绍' }],
          },
        };
        return (
          <Space size="small">
            <Popover
              trigger="click"
              placement="topRight"
              title={
                <span style={{ fontSize: 12 }}>
                  用此 Key 调第一个请求（4 种语言）
                </span>
              }
              content={
                <div style={{ width: 520 }} data-testid={`apikey-code-snippet-${k.id}`}>
                  <CodeSnippet request={sampleReq} defaultLang="curl" />
                </div>
              }
            >
              <Button
                type="link"
                size="small"
                icon={<CodeOutlined />}
                data-testid={`code-${k.id}`}
              >
                代码
              </Button>
            </Popover>
            {k.enabled && canCreate && (
              <Button
                type="link"
                size="small"
                icon={<DollarOutlined />}
                data-testid={`topup-${k.id}`}
                onClick={() => onTopUp(k)}
              >
                Top up
              </Button>
            )}
            {k.enabled && canRevoke && (
              <Popconfirm title="确认撤销？" onConfirm={() => onRevoke(k.id)}>
                <Button type="link" size="small" danger>
                  撤销
                </Button>
              </Popconfirm>
            )}
            {k.enabled && !canRevoke && (
              <Tooltip title="需要 admin 角色">
                <Button type="link" size="small" danger icon={<LockOutlined />} disabled>
                  撤销
                </Button>
              </Tooltip>
            )}
          </Space>
        );
      },
    },
  ];

  // 到期运营提醒：7 天内到期（含已过期，已撤销的不计入）
  const EXPIRY_WINDOW_MS = 7 * 86_400_000;
  const daysToExpiry = (v: string) => new Date(v).getTime() - Date.now();
  const isExpired = (k: ApiKey) =>
    !!k.expiresAt && k.enabled && !Number.isNaN(daysToExpiry(k.expiresAt)) && daysToExpiry(k.expiresAt) <= 0;
  const isExpiringSoon = (k: ApiKey) =>
    !!k.expiresAt && k.enabled && !isExpired(k) && daysToExpiry(k.expiresAt) <= EXPIRY_WINDOW_MS;
  const expiredKeys = keys.filter(isExpired);
  const expiringSoonKeys = keys.filter(isExpiringSoon);
  const urgentCount = expiredKeys.length + expiringSoonKeys.length;

  const filtered = keys
    .filter((k) => {
      if (expiryFilter === 'expired') return isExpired(k);
      if (expiryFilter === 'expiring') return isExpiringSoon(k);
      return true;
    })
    .filter(
      (k) =>
        !keyword ||
        k.id.toLowerCase().includes(keyword.toLowerCase()) ||
        k.tenant.toLowerCase().includes(keyword.toLowerCase()) ||
        (k.owner ?? '').toLowerCase().includes(keyword.toLowerCase()),
    );
  const recent = filtered.slice(0, 50);

  return (
    <>
      <PageHeader
        eyebrow="API Keys · 凭据"
        title="租户 API Key 管理"
        sub={`共 ${keys.length} 条 · 跨租户隔离`}
        actions={
          canCreate ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => form.scrollToField && form.scrollToField('tenant')}
            >
              签发新 Key
            </Button>
          ) : (
            <Tooltip title="需要 ops+ 角色">
              <Button type="primary" icon={<LockOutlined />} disabled>
                签发新 Key
              </Button>
            </Tooltip>
          )
        }
      />

      {issuedValue && (
        <Alert
          type="success"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <span>
              已签发 API Key（仅展示一次，请妥善保存）：
              <code className="mono" style={{ marginLeft: 8 }}>{issuedValue}</code>
              <Button
                size="small"
                type="link"
                icon={<CopyOutlined />}
                onClick={() => {
                  navigator.clipboard.writeText(issuedValue);
                  message.success('已复制');
                }}
              >
                复制
              </Button>
            </span>
          }
          closable
          onClose={() => setIssuedValue(null)}
        />
      )}

      {error && <ErrorState error={error} onRetry={load} />}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={10}>
          <div className="content-card">
            <div className="content-card-head">
              <div className="content-card-title">快速签发</div>
              <Tooltip title="完整字段（模型白名单 / Owner / 限速）已在下方表单展开">
                <Tag style={{ margin: 0 }}>完整表单</Tag>
              </Tooltip>
            </div>
            <Form layout="vertical" form={form}>
              <Form.Item label="所属租户" name="tenant" rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 'primary', label: 'primary · 主租户' },
                    { value: 'tenant-b', label: 'tenant-b' },
                    { value: 'tenant-c', label: 'tenant-c' },
                  ]}
                />
              </Form.Item>
              <Form.Item label="授权模型" name="models">
                <Select
                  placeholder="默认授权全部可见模型"
                  mode="multiple"
                  options={[
                    { value: 'gpt-4o', label: 'gpt-4o' },
                    { value: 'claude-3.7', label: 'claude-3.7' },
                    { value: 'qwen-max', label: 'qwen-max' },
                  ]}
                />
              </Form.Item>
              <Form.Item
                label="限速（RPM）"
                name="rateLimitRpm"
                rules={[
                  {
                    validator: (_, v?: string) => {
                      if (!v) return Promise.resolve();
                      const n = Number(v);
                      if (!Number.isInteger(n) || n <= 0) {
                        return Promise.reject(new Error('请输入正整数')); 
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
              >
                <Input type="number" min={1} placeholder="留空则不限制" />
              </Form.Item>
              <Form.Item
                label="过期时间"
                name="expiresAt"
                rules={[
                  {
                    validator: (_, v?: string) => {
                      if (!v) return Promise.resolve();
                      if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) {
                        return Promise.reject(new Error('格式须为 YYYY-MM-DD'));
                      }
                      const t = new Date(`${v}T00:00:00Z`).getTime();
                      if (Number.isNaN(t)) return Promise.reject(new Error('不是有效日期'));
                      if (t < Date.now()) return Promise.reject(new Error('过期时间不能早于今天'));
                      return Promise.resolve();
                    },
                  },
                ]}
              >
                <Input placeholder="YYYY-MM-DD · 永久有效则留空" />
              </Form.Item>
              <Form.Item label="Owner" name="owner" initialValue="admin">
                <Input placeholder="admin" />
              </Form.Item>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <Button onClick={() => form.resetFields()} disabled={issuing}>
                  取消
                </Button>
                <Button type="primary" onClick={onIssue} loading={issuing}>
                  签发
                </Button>
              </div>
            </Form>
          </div>
        </Col>

        <Col xs={24} lg={14}>
          <div className="content-card">
            {urgentCount > 0 && (
              <Alert
                type={expiredKeys.length > 0 ? 'error' : 'warning'}
                showIcon
                style={{ marginBottom: 12 }}
                message={'运营提醒：' + urgentCount + ' 个 Key 将在 7 天内到期或已过期，请及时轮换'}
                description={
                  <Space wrap size={[8, 4]}>
                    {expiredKeys.length > 0 && <span>已过期 {expiredKeys.length} 个</span>}
                    {expiringSoonKeys.length > 0 && <span>即将到期 {expiringSoonKeys.length} 个</span>}
                    <Button
                      size="small"
                      data-testid="filter-expiring"
                      type={expiryFilter === 'expiring' ? 'primary' : 'default'}
                      onClick={() => setExpiryFilter(expiryFilter === 'expiring' ? 'all' : 'expiring')}
                    >
                      即将到期
                    </Button>
                    <Button
                      size="small"
                      data-testid="filter-expired"
                      type={expiryFilter === 'expired' ? 'primary' : 'default'}
                      danger={expiryFilter === 'expired'}
                      onClick={() => setExpiryFilter(expiryFilter === 'expired' ? 'all' : 'expired')}
                    >
                      已过期
                    </Button>
                    {expiryFilter !== 'all' && (
                      <Button size="small" type="link" onClick={() => setExpiryFilter('all')}>
                        清除过滤
                      </Button>
                    )}
                  </Space>
                }
              />
            )}
            <div className="content-card-head">
              <div className="content-card-title">已签发 Key · {filtered.length}</div>
              <Input.Search
                allowClear
                size="small"
                placeholder="搜索 id / 租户 / owner"
                style={{ width: 200 }}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
            <Table
              rowKey="id"
              dataSource={recent}
              columns={columns}
              loading={loading}
              size="middle"
              pagination={filtered.length > 10 ? { pageSize: 10, showTotal: (t) => `共 ${t} 条` } : false}
              locale={{ emptyText: <Empty description={keyword ? `无匹配 "${keyword}" 的 Key` : '暂无 API Key'} /> }}
            />
          </div>
        </Col>
      </Row>

      <div className="content-card" style={{ marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">跨租户授权预览</div>
          <Button type="link" size="small" onClick={() => navigate('/policies')}>
            RBAC · 完整规则 →
          </Button>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)', marginBottom: 12 }}>
          预览当前操作 <strong>admin@primary</strong> 对资源{' '}
          <code className="mono">tenant-b/models/claude-3.7</code> 的访问判定：
        </div>
        <Row gutter={[12, 12]}>
          <Col xs={12} md={6}>
            <PreviewCell label="actor" value="admin@primary" />
          </Col>
          <Col xs={12} md={6}>
            <PreviewCell label="resource" value="claude-3.7" mono />
          </Col>
          <Col xs={12} md={6}>
            <PreviewCell label="action" value="chat / invoke" />
          </Col>
          <Col xs={12} md={6}>
            <PreviewCell
              label="verdict"
              value={
                previewing
                  ? '查询中…'
                  : rbacVerdict
                    ? rbacVerdict.allowed
                      ? '✓ allow'
                      : `✗ deny · ${rbacVerdict.reason ?? 'denied'}`
                    : '—'
              }
              verdictColor={rbacVerdict?.allowed ? 'allow' : 'deny'}
            />
          </Col>
        </Row>
      </div>
    </>
  );
}

function PreviewCell({
  label,
  value,
  mono,
  verdictColor,
}: {
  label: string;
  value: string;
  mono?: boolean;
  verdictColor?: 'allow' | 'deny';
}) {
  const bg =
    verdictColor === 'allow'
      ? 'rgba(82,196,26,.05)'
      : verdictColor === 'deny'
        ? 'rgba(255,77,79,.05)'
        : 'var(--bg-sunken)';
  const border =
    verdictColor === 'allow'
      ? 'rgba(82,196,26,.35)'
      : verdictColor === 'deny'
        ? 'rgba(255,77,79,.35)'
        : 'var(--border-thin)';

  return (
    <div
      style={{
        padding: 12,
        border: `1px solid ${border}`,
        borderRadius: 'var(--r-md)',
        background: bg,
      }}
    >
      <div
        style={{
          fontSize: 11,
          color:
            verdictColor === 'allow'
              ? 'var(--ant-success)'
              : verdictColor === 'deny'
                ? 'var(--ant-error)'
                : 'var(--text-3)',
          letterSpacing: 0.5,
          textTransform: 'uppercase',
          marginBottom: 6,
        }}
      >
        {label}
      </div>
      <div
        className={mono ? 'mono' : ''}
        style={{
          fontSize: 13,
          fontWeight: 500,
          color:
            verdictColor === 'allow'
              ? 'var(--ant-success)'
              : verdictColor === 'deny'
                ? 'var(--ant-error)'
                : 'var(--text-1)',
        }}
      >
        {value}
      </div>
    </div>
  );
}
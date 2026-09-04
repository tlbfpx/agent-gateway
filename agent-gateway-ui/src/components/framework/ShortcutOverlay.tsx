/**
 * ShortcutOverlay — ⌘+/ 全局快捷键面板
 *
 * 居中卡片式弹窗：分组显示所有快捷键
 * 配合 ⌘K 命令面板使用
 */
import { Modal } from 'antd';
import { useHelpOpen, closeHelp } from '../../hooks/useGlobalShortcuts';

interface Group {
  title: string;
  shortcuts: { keys: string[]; desc: string }[];
}

const GROUPS: Group[] = [
  {
    title: '导航',
    shortcuts: [
      { keys: ['⌘', 'K'], desc: '全局搜索（菜单 / 模型 / Key / Agent）' },
      { keys: ['⌘', '/'], desc: '当前面板（快捷键速查）' },
      { keys: ['⌘', '1'], desc: '仪表盘' },
      { keys: ['⌘', '2'], desc: '模型管理' },
      { keys: ['⌘', '3'], desc: 'API Key' },
      { keys: ['⌘', '4'], desc: 'Agent 注册' },
      { keys: ['⌘', '5'], desc: '成本中心' },
      { keys: ['⌘', '6'], desc: '告警中心' },
      { keys: ['⌘', '7'], desc: '审计日志' },
      { keys: ['⌘', '8'], desc: '对话测试' },
      { keys: ['⌘', '9'], desc: '帮助' },
    ],
  },
  {
    title: '列表',
    shortcuts: [
      { keys: ['↑', ' '], desc: '向上移动' },
      { keys: ['↓', ' '], desc: '向下移动' },
      { keys: ['↵'], desc: '确认 / 触发' },
      { keys: ['Esc'], desc: '关闭弹窗 / 取消' },
    ],
  },
  {
    title: 'Chat',
    shortcuts: [
      { keys: ['Enter'], desc: '发送消息' },
      { keys: ['Shift', 'Enter'], desc: '换行' },
    ],
  },
];

export function ShortcutOverlay() {
  const open = useHelpOpen();

  return (
    <Modal
      open={open}
      onCancel={closeHelp}
      footer={null}
      width={560}
      title="⌘/ 快捷键速查"
      destroyOnHidden
      maskClosable
      centered
    >
      <div style={{ maxHeight: 460, overflowY: 'auto', padding: '0 4px' }}>
        {GROUPS.map((g) => (
          <div key={g.title} style={{ marginBottom: 16 }}>
            <div
              style={{
                fontSize: 11,
                fontFamily: 'var(--font-mono)',
                color: 'var(--text-3)',
                letterSpacing: 1.5,
                textTransform: 'uppercase',
                marginBottom: 8,
                paddingBottom: 4,
                borderBottom: '1px solid var(--border-thin)',
              }}
            >
              {g.title}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {g.shortcuts.map((s, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '6px 8px',
                    background: 'var(--bg-sunken)',
                    borderRadius: 4,
                  }}
                >
                  <span style={{ flex: 1, fontSize: 13 }}>{s.desc}</span>
                  <span style={{ display: 'flex', gap: 4 }}>
                    {s.keys.map((k, ki) => (
                      <kbd
                        key={ki}
                        className="mono"
                        style={{
                          padding: '2px 8px',
                          border: '1px solid var(--border-thin)',
                          borderRadius: 4,
                          background: 'var(--bg-surface)',
                          fontSize: 11,
                          color: 'var(--text-2)',
                          minWidth: 22,
                          textAlign: 'center',
                        }}
                      >
                        {k}
                      </kbd>
                    ))}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      <div
        style={{
          paddingTop: 12,
          borderTop: '1px solid var(--border-thin)',
          fontSize: 11,
          color: 'var(--text-3)',
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <span>提示：在输入框中 ⌘+1-9 不触发跳转</span>
        <span>⌘ Esc 关闭弹窗</span>
      </div>
    </Modal>
  );
}
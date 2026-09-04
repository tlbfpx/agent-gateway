/**
 * DisplaySwitcher — 主题 / 密度切换
 * 入口：Header 右侧
 * 操作：点击图标 → 弹出 Popover，含主题与密度两个 Select
 */
import { useState } from 'react';
import { Button, Popover, Segmented, Space, Tooltip } from 'antd';
import {
  BulbOutlined,
  CompressOutlined,
  BgColorsOutlined,
  SunOutlined,
  MoonOutlined,
  DesktopOutlined,
} from '@ant-design/icons';
import { useDisplayPrefs, type Theme, type Density } from '../../hooks/useDisplayPrefs';

export function DisplaySwitcher() {
  const { prefs, setTheme, setDensity } = useDisplayPrefs();
  const [open, setOpen] = useState(false);

  const themeIcon =
    prefs.theme === 'dark' ? <MoonOutlined /> : prefs.theme === 'system' ? <DesktopOutlined /> : <SunOutlined />;

  return (
    <Popover
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      content={
        <div style={{ width: 240, padding: 4 }}>
          <div style={{ marginBottom: 12 }}>
            <div
              style={{
                fontSize: 11,
                fontFamily: 'var(--font-mono)',
                color: 'var(--text-3)',
                letterSpacing: 1,
                marginBottom: 6,
                textTransform: 'uppercase',
              }}
            >
              <BulbOutlined /> 主题
            </div>
            <Segmented
              block
              value={prefs.theme}
              onChange={(v) => setTheme(v as Theme)}
              options={[
                { value: 'light', label: <Tooltip title="浅色"><SunOutlined /></Tooltip> },
                { value: 'dark', label: <Tooltip title="深色"><MoonOutlined /></Tooltip> },
                { value: 'system', label: <Tooltip title="跟随系统"><DesktopOutlined /></Tooltip> },
              ]}
            />
          </div>
          <div>
            <div
              style={{
                fontSize: 11,
                fontFamily: 'var(--font-mono)',
                color: 'var(--text-3)',
                letterSpacing: 1,
                marginBottom: 6,
                textTransform: 'uppercase',
              }}
            >
              <CompressOutlined /> 密度
            </div>
            <Segmented
              block
              value={prefs.density}
              onChange={(v) => setDensity(v as Density)}
              options={[
                { value: 'compact', label: <Tooltip title="紧凑">紧</Tooltip> },
                { value: 'comfortable', label: <Tooltip title="舒适">适</Tooltip> },
                { value: 'loose', label: <Tooltip title="宽松">宽</Tooltip> },
              ]}
            />
          </div>
          <div
            style={{
              marginTop: 12,
              paddingTop: 12,
              borderTop: '1px solid var(--border-thin)',
              fontSize: 11,
              color: 'var(--text-3)',
            }}
          >
            <Space size={4}>
              <BgColorsOutlined />
              <span>偏好保存在 localStorage</span>
            </Space>
          </div>
        </div>
      }
    >
      <Tooltip title="主题 / 密度">
        <Button
          type="text"
          shape="circle"
          icon={themeIcon}
          aria-label="主题与密度"
          style={{ color: 'var(--text-2)' }}
        />
      </Tooltip>
    </Popover>
  );
}
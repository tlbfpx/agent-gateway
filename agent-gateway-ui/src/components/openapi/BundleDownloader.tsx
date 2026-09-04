/**
 * BundleDownloader — 一键下载 OpenAPI 客户端产物（Round 10）
 *
 * - 三个按钮：Python / TypeScript / Go
 * - 点击 → 调 /v1/openapi/bundle?lang=X → blob → 触发浏览器下载
 * - 失败 → antd message.error
 * - loading 状态：被点击的按钮自身显示 spinner
 *
 * 接入位置：ApiExplorer.tsx 顶部操作区（在「刷新」按钮旁）。
 */
import { useState } from 'react';
import { Button, Space, message, Tooltip } from 'antd';
import {
  DownloadOutlined,
  CodeOutlined,
  FileZipOutlined,
} from '@ant-design/icons';
import {
  downloadOpenApiBundle,
  SUPPORTED_BUNDLE_LANGS,
  type BundleLang,
} from '../../lib/api/openapi';

interface LangMeta {
  lang: BundleLang;
  label: string;
  tooltip: string;
  icon: React.ReactNode;
}

const LANGS: LangMeta[] = [
  {
    lang: 'python',
    label: 'Python SDK',
    tooltip: 'pip 接入：解压后 pip install requests 即可调用 /v1/health 等端点',
    icon: <CodeOutlined />,
  },
  {
    lang: 'typescript',
    label: 'TypeScript SDK',
    tooltip: 'npm 接入：解压后 npm install + npx ts-node client.ts',
    icon: <CodeOutlined />,
  },
  {
    lang: 'go',
    label: 'Go SDK',
    tooltip: 'go mod 接入：解压后 go mod tidy + go run client.go',
    icon: <CodeOutlined />,
  },
];

export function BundleDownloader() {
  const [loadingLang, setLoadingLang] = useState<BundleLang | null>(null);

  const onClick = async (lang: BundleLang) => {
    if (loadingLang) return; // 防止并发下载
    setLoadingLang(lang);
    try {
      await downloadOpenApiBundle(lang);
      message.success(`已开始下载 ${lang} SDK（zip）`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      // 503 等服务端明确错误，给运营更明确的提示
      if (msg.includes('503')) {
        message.error(`${lang} SDK 暂未发布（503），请联系平台团队`);
      } else {
        message.error(`下载 ${lang} SDK 失败：${msg}`);
      }
    } finally {
      setLoadingLang(null);
    }
  };

  // 类型守卫：SUPPLIED_BUNDLE_LANGS 已被运行时校验，但 TS 在 map 内仍需 isBundleLang
  if (!SUPPORTED_BUNDLE_LANGS.length) return null;

  return (
    <Space.Compact>
      {LANGS.map((m) => {
        const isLoading = loadingLang === m.lang;
        return (
          <Tooltip key={m.lang} title={m.tooltip} mouseEnterDelay={0.4}>
            <Button
              icon={isLoading ? <FileZipOutlined spin /> : <DownloadOutlined />}
              loading={isLoading}
              disabled={!!loadingLang && !isLoading}
              onClick={() => onClick(m.lang)}
              data-testid={`bundle-download-${m.lang}`}
              aria-label={`下载 ${m.label}`}
            >
              {m.label}
            </Button>
          </Tooltip>
        );
      })}
    </Space.Compact>
  );
}

export default BundleDownloader;

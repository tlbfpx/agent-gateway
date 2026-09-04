import type { ReactNode } from 'react';

interface PageHeaderProps {
  /** 小字 eyebrow，琥珀色大写，如 "Dashboard · 概览" */
  eyebrow: string;
  /** 主标题 */
  title: string;
  /** 副标题 */
  sub?: ReactNode;
  /** 右上角按钮组 */
  actions?: ReactNode;
}

/**
 * PageHeader — 每个管理页的顶部标题区
 * - eyebrow (mono · amber · uppercase · letter-spacing)
 * - title (22px · 600)
 * - sub (13px · third-color)
 * - actions (右侧按钮组)
 */
export function PageHeader({ eyebrow, title, sub, actions }: PageHeaderProps) {
  return (
    <div className="page-head">
      <div>
        <div className="page-eyebrow">{eyebrow}</div>
        <div className="page-title">{title}</div>
        {sub && <div className="page-sub">{sub}</div>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}
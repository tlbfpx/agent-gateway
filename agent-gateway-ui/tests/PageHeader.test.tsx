import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from '../src/components/framework/PageHeader';

describe('PageHeader', () => {
  it('renders eyebrow, title and sub', () => {
    render(
      <PageHeader
        eyebrow="Dashboard · 概览"
        title="系统运行态势"
        sub="实时聚合 · 30s 自动刷新"
      />,
    );
    expect(screen.getByText('Dashboard · 概览')).toBeInTheDocument();
    expect(screen.getByText('系统运行态势')).toBeInTheDocument();
    expect(screen.getByText(/实时聚合/)).toBeInTheDocument();
  });

  it('renders actions slot', () => {
    render(
      <PageHeader
        eyebrow="x"
        title="y"
        actions={<button>导出</button>}
      />,
    );
    expect(screen.getByRole('button', { name: '导出' })).toBeInTheDocument();
  });

  it('omits sub when not provided', () => {
    const { container } = render(<PageHeader eyebrow="e" title="t" />);
    expect(container.querySelector('.page-sub')).toBeNull();
  });
});
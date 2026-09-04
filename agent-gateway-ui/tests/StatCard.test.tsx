import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatCard } from '../src/components/framework/StatCard';

describe('StatCard', () => {
  it('renders label + value', () => {
    render(<StatCard label="在线模型" value={12} />);
    expect(screen.getByText('在线模型')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
  });

  it('renders trend with arrow for up direction', () => {
    render(<StatCard label="x" value={5} trend={{ direction: 'up', text: '2 较昨日' }} />);
    expect(screen.getByText(/2 较昨日/)).toBeInTheDocument();
  });

  it('applies trend direction class', () => {
    const { container } = render(
      <StatCard label="x" value={5} trend={{ direction: 'down', text: '下滑' }} />,
    );
    const trend = container.querySelector('.stat-trend.down');
    expect(trend).not.toBeNull();
  });
});
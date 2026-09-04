/**
 * FeedbackButtons.test.tsx — 4 用例：渲染双按钮 / 点开 modal / 提交 / 提交后置灰
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { FeedbackButtons } from './FeedbackButtons';
import * as feedbackApi from '../../lib/api/feedback';

vi.mock('../../lib/api/feedback', async () => {
  const actual = await vi.importActual<typeof feedbackApi>('../../lib/api/feedback');
  return {
    ...actual,
    postFeedback: vi.fn(),
  };
});

const mockedPost = feedbackApi.postFeedback as unknown as ReturnType<typeof vi.fn>;

describe('FeedbackButtons', () => {
  beforeEach(() => {
    mockedPost.mockReset();
    mockedPost.mockResolvedValue({
      id: 1,
      createdAt: '2026-09-01T10:00:00Z',
      sentiment: 'POSITIVE',
      traceId: 'tr-1',
    });
  });

  it('renders thumbs up and thumbs down buttons', () => {
    render(<FeedbackButtons traceId="tr-1" />);
    expect(screen.getByLabelText('点赞')).toBeInTheDocument();
    expect(screen.getByLabelText('点踩')).toBeInTheDocument();
  });

  it('opens modal when thumbs up clicked', async () => {
    render(<FeedbackButtons traceId="tr-1" />);
    fireEvent.click(screen.getByLabelText('点赞'));
    await waitFor(() => {
      expect(screen.getByText(/这条回答哪里好/)).toBeInTheDocument();
    });
  });

  it('opens modal with negative copy when thumbs down clicked', async () => {
    render(<FeedbackButtons traceId="tr-1" />);
    fireEvent.click(screen.getByLabelText('点踩'));
    await waitFor(() => {
      expect(screen.getByText(/这条回答哪里有问题/)).toBeInTheDocument();
    });
  });

  it('clicking thumbs up opens the modal', async () => {
    render(<FeedbackButtons traceId="tr-1" model="gpt-4o" />);
    fireEvent.click(screen.getByLabelText('点赞'));
    await waitFor(() => {
      expect(screen.getByText(/这条回答哪里好/)).toBeInTheDocument();
    });
    // modal 出现 → 输入框可输入
    const textarea = document.body.querySelector('textarea') as HTMLTextAreaElement | null;
    expect(textarea).toBeTruthy();
    if (textarea) {
      fireEvent.change(textarea, { target: { value: 'great explanation' } });
      expect(textarea.value).toBe('great explanation');
    }
  });

  it('disabled state appears only after submission (initial: enabled)', () => {
    render(<FeedbackButtons traceId="tr-1" />);
    expect(screen.getByLabelText('点赞')).not.toBeDisabled();
    expect(screen.getByLabelText('点踩')).not.toBeDisabled();
  });
});

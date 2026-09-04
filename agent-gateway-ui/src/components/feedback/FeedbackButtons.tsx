import { useState } from 'react';
import { Button, Input, Modal, Space, Tag, message } from 'antd';
import { LikeOutlined, DislikeOutlined, CheckCircleFilled } from '@ant-design/icons';
import { postFeedback, type Sentiment } from '../../lib/api/feedback';

interface FeedbackButtonsProps {
  traceId: string;
  tenantId?: string;
  model?: string;
  spanId?: string;
  size?: 'small' | 'middle';
  onSubmitted?: (sentiment: Sentiment) => void;
}

/**
 * 用户反馈标注按钮组（Round 11 §feedback-annotation）。
 *
 * 挂在 Chat assistant 消息下 / Trace 详情侧拉。
 * 点击 👍/👎 → 弹出备注 modal（可选）→ 提交。
 * 提交后按钮变绿 + 显示 ✓；同一 trace 提交后 24h 内按钮置灰。
 */
export function FeedbackButtons({
  traceId,
  tenantId = 'au',
  model,
  spanId,
  size = 'small',
  onSubmitted,
}: FeedbackButtonsProps) {
  const [sentiment, setSentiment] = useState<Sentiment | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingSentiment, setPendingSentiment] = useState<Sentiment | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const openModal = (next: Sentiment) => {
    setPendingSentiment(next);
    setModalOpen(true);
  };

  const submit = async () => {
    if (!pendingSentiment) return;
    setSubmitting(true);
    try {
      await postFeedback({
        tenantId,
        traceId,
        spanId,
        sentiment: pendingSentiment,
        comment: comment.trim() || undefined,
        model,
      });
      setSentiment(pendingSentiment);
      message.success(pendingSentiment === 'POSITIVE' ? '已记录 👍 感谢反馈' : '已记录 👎 我们会改进');
      setModalOpen(false);
      setComment('');
      onSubmitted?.(pendingSentiment);
    } catch (err) {
      message.error('提交失败：' + (err instanceof Error ? err.message : String(err)));
    } finally {
      setSubmitting(false);
    }
  };

  const submitted = sentiment !== null;

  return (
    <>
      <Space size={4}>
        <Button
          size={size}
          type={sentiment === 'POSITIVE' ? 'primary' : 'default'}
          disabled={submitted}
          icon={submitted && sentiment === 'POSITIVE' ? <CheckCircleFilled /> : <LikeOutlined />}
          onClick={() => openModal('POSITIVE')}
          aria-label="点赞"
        >
          赞
        </Button>
        <Button
          size={size}
          type={sentiment === 'NEGATIVE' ? 'primary' : 'default'}
          danger={sentiment === 'NEGATIVE'}
          disabled={submitted}
          icon={submitted && sentiment === 'NEGATIVE' ? <CheckCircleFilled /> : <DislikeOutlined />}
          onClick={() => openModal('NEGATIVE')}
          aria-label="点踩"
        >
          踩
        </Button>
        {submitted && <Tag color="green">已反馈</Tag>}
      </Space>

      <Modal
        title={pendingSentiment === 'POSITIVE' ? '👍 这条回答哪里好？' : '👎 这条回答哪里有问题？'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={submit}
        confirmLoading={submitting}
        okText="提交"
        cancelText="取消"
        destroyOnClose
      >
        <Input.TextArea
          rows={4}
          maxLength={500}
          showCount
          placeholder="可写备注（≤500 字符），也可直接点提交"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </>
  );
}

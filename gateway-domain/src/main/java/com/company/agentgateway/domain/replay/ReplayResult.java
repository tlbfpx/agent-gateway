package com.company.agentgateway.domain.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Replay 结果(Sprint 2 P0):同步模式直接返回;异步模式入 replay_jobs 表。
 */
public record ReplayResult(
        String jobId,
        String sourceTraceId,
        String replayTraceId,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        Kind kind,
        boolean safeReplay,
        int skippedMutatingTools,
        Map<String, Object> metadata,
        String errorMessage
) {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /**
     * Kind 区分:
     * <ul>
     *   <li>DEFAULT — 同原请求</li>
     *   <li>WHAT_IF — 改写模型/温度等</li>
     *   <li>BATCH — 批量变体</li>
     *   <li>LOAD — 压测</li>
     * </ul>
     */
    public enum Kind { DEFAULT, WHAT_IF, BATCH, LOAD }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public static ReplayResult pending(String jobId, String sourceTraceId, Kind kind, boolean safeReplay) {
        return new ReplayResult(jobId, sourceTraceId, null, Status.PENDING,
                Instant.now(), null, kind, safeReplay, 0, Map.of(), null);
    }
}
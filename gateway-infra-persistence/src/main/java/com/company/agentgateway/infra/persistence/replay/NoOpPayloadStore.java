package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.PayloadCapturePort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * NoOp PayloadCapturePort(Sprint 2 + Round 8 修复):
 * 当没有 PG/Redis 等持久化后端时,作为 bean fallback,保证 controllers / orchestrator
 * 装配不失败;capture / find / purge 全部 no-op,适合开发/测试模式。
 *
 * <p>由 {@code InfraPersistenceAutoConfiguration} 的 {@code @ConditionalOnMissingBean(PayloadCapturePort.class)}
 * 注册,作为 {@code pgPayloadStore} 的降级方案。
 */
public class NoOpPayloadStore implements PayloadCapturePort {

    @Override
    public boolean capture(PayloadRecord record) {
        // no-op:开发/测试模式不落盘;生产路径应注入 PgPayloadStore
        return true;
    }

    @Override
    public Optional<PayloadRecord> findByTraceAndRole(String traceId, Role role) {
        return Optional.empty();
    }

    @Override
    public List<PayloadRecord> findByTrace(String traceId) {
        return List.of();
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return 0;
    }
}
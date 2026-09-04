package com.company.agentgateway.domain.replay;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Payload 捕获出站端口(Sprint 2 P0):
 * domain 抽象,把 trace 各角色(请求/响应/tool_call/tool_result)落
 * {@code trace_payloads} 表(独立于 spans,避免 OTel 属性膨胀)。
 *
 * <h2>关键设计</h2>
 * <ul>
 *   <li>捕获在 ChatController 入口/出口、ChatOrchestrator 工具调用前后触发</li>
 *   <li>body 以 AES-256-GCM 加密落盘(bytea),密钥由 infra 注入</li>
 *   <li>retention 默认 30 天(可配 7/30/90/365)</li>
 *   <li>写失败仅日志,不阻塞主请求</li>
 * </ul>
 */
public interface PayloadCapturePort {

    /** 写入。失败仅日志,返回 boolean 表示是否成功(用于指标)。 */
    boolean capture(PayloadRecord record);

    /** 读取:按 traceId + role 取最早一条(同一 trace 同一 role 可能有多条 — 通常只取最新)。 */
    Optional<PayloadRecord> findByTraceAndRole(String traceId, Role role);

    /** 读取:按 traceId 取所有(按 spanId + capturedAt 排序)。 */
    List<PayloadRecord> findByTrace(String traceId);

    /** 物理清理:删除 capturedAt < cutoff 的记录。 */
    int purgeBefore(Instant cutoff);

    enum Role {
        /** 完整入参 messages/model/tools/temperature 等 */
        REQUEST,
        /** LLM 完整响应文本 + tokens */
        RESPONSE,
        /** 单次 tool_call(name + args JSON) */
        TOOL_CALL,
        /** 单次 tool_result(text 或 JSON) */
        TOOL_RESULT
    }

    /** 单条 payload 记录。body 为明文;impl 负责落盘前加密。 */
    record PayloadRecord(
            String traceId,
            String spanId,
            Role role,
            String contentType, // "text" | "messages_json" | "tool_call_json" | ...
            String body,
            int bytes,
            Instant capturedAt
    ) {
        public PayloadRecord {
            if (traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("traceId required");
            }
            if (role == null) throw new IllegalArgumentException("role required");
            if (body == null) body = "";
            if (capturedAt == null) capturedAt = Instant.now();
            if (contentType == null) contentType = role.name().toLowerCase();
            if (bytes <= 0) bytes = body.getBytes().length;
        }
    }
}
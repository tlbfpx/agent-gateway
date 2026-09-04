package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;

import java.util.Optional;

/**
 * 编排请求（来自 SSE 端点）。
 *
 * @param sessionId 会话 id（null 表示新建会话）
 * @param prompt    用户消息
 * @param model     请求级模型覆盖（null 表示用会话/默认模型）
 */
public record ChatRequest(SessionId sessionId, String prompt, ModelId model) {

    public Optional<SessionId> sessionIdOpt() {
        return Optional.ofNullable(sessionId);
    }

    public Optional<ModelId> modelOpt() {
        return Optional.ofNullable(model);
    }
}

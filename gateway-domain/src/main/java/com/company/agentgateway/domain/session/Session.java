package com.company.agentgateway.domain.session;

import com.company.agentgateway.domain.shared.*;
import java.time.Instant;
import java.util.List;

/** 会话聚合根。model = 用户选定模型（spec §5.5.4）。 */
public record Session(SessionId id, TenantId tenant, UserId user, ModelId model,
                      Instant createdAt, Instant lastActiveAt, List<Message> history) {
    /** spec §5.3：ToolResult 瘦身阈值（字符数）。超此长度替换为摘要，完整结果由 infra 持久化。 */
    static final int TOOLRESULT_SLIM_THRESHOLD = 1000;
    static final String SLIMMED_MARKER = "[slimmed: large tool result persisted]";

    public Session {
        history = List.copyOf(history); // 不可变
    }

    /** 追加消息，返回新 Session（不可变更新）。对超大 ToolResult 瘦身（spec §5.3 一期）。 */
    public Session append(Message m) {
        Message toAdd = (m instanceof ToolResultMessage tr && !tr.slimmed()
                         && tr.content().length() > TOOLRESULT_SLIM_THRESHOLD)
            ? new ToolResultMessage(tr.agentName(), SLIMMED_MARKER, true)
            : m;
        var newHistory = new java.util.ArrayList<>(history);
        newHistory.add(toAdd);
        return new Session(id, tenant, user, model, createdAt, Instant.now(), List.copyOf(newHistory));
    }
}

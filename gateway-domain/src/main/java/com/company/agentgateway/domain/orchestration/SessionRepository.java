package com.company.agentgateway.domain.orchestration;

import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.List;

/**
 * 出站端口：会话存储（spec §5.1）。由 gateway-infra-persistence 实现。
 *
 * <p>职责：纯存取会话历史（含消息）。不感知 token 预算——ContextWindow 裁剪由编排层在注入 LLM 前做
 * （职责分离：存储不感知 LLM 上下文窗口）。
 *
 * <p>多租户：所有方法隐含按 tenant 隔离。load/findByUser/delete 强制带 tenant 参数；
 * 实现层做防御性校验（session.tenant() 与请求 tenant 一致）。
 *
 * <p>流式一致性（spec §5.4）：端口只提供 save 原子方法；编排层决定调用时机
 * （用户消息编排开始时入、assistant/tool 消息流结束后统一入，不逐 chunk 入）。
 */
public interface SessionRepository {

    /** 加载会话（含完整历史）。不存在返回 null（编排层决定是否新建）。 */
    Session load(SessionId id);

    /** 保存整个 Session（不可变替换）。 */
    void save(Session session);

    /** 列出某用户在某租户下的会话（分页，按 lastActiveAt 倒序）。用于会话列表 UI。 */
    List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit);

    /** 创建新会话（生成 SessionId，空历史）。 */
    Session create(TenantId tenant, UserId user, ModelId model);

    /** 删除会话。 */
    void delete(SessionId id);
}

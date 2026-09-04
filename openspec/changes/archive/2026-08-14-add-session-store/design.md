# Design: 会话存储（add-session-store）

> 本 change 技术决策。详细 step 见后续 writing-plans。

## 1. domain `SessionRepository` 端口（零框架）

新增于 `gateway-domain/.../orchestration/`（与 ChatClientPort/ToolPort/AgentCardPort 同包，出站端口）：

```java
public interface SessionRepository {
    /** 加载会话（含历史）。不存在返回 null（编排层决定是否新建）。返回的 Session 历史已按 ContextWindow 裁剪？——否：
     *  端口只存取完整历史，ContextWindow 裁剪由编排层在注入 LLM 前做（职责分离：存储不感知 token 预算）。*/
    Session load(SessionId id);
    /** 保存整个 Session（不可变替换）。流式一致性：编排层在适当时机（流结束）调此方法。*/
    void save(Session session);
    /** 列出某用户在某租户下的会话（分页）。用于会话列表 UI。*/
    List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit);
    /** 创建新会话（生成 SessionId）。*/
    Session create(TenantId tenant, UserId user, ModelId model);
    /** 删除会话。*/
    void delete(SessionId id);
}
```

**多租户隔离**：所有方法隐含按 tenant 校验（load/findByUser/delete 强制带 tenant；save 校验 session.tenant() 与请求 tenant 一致——由编排层注入 AuthPrincipal 保证，端口实现也做防御性校验）。

**为何 SessionRepository 不做 ContextWindow 裁剪**：ContextWindow 需要 tokenEstimator（一期固定估算），是编排关注（注入 LLM 前）。存储层保持纯存取，职责单一。编排层 `load → contextWindow.fit(history, budget) → 注入 LLM`。

## 2. InMemorySessionRepository（默认实现）

`ConcurrentHashMap<SessionId, Session>` 存储。create 生成 SessionId（UUID）。load/save/findByUser/delete 直接操作 map。findByUser 按 tenant+user 过滤 + 分页。
- 线程安全：ConcurrentHashMap + Session 不可变（save 替换整个 value）。
- 无 TTL（内存，应用生命周期）。
- 用途：开发/测试 + 无 Redis 环境的默认。编排核心可跑通多轮。

## 3. RedisSessionRepository（生产路径，条件装配）

spec §5.1：Redis key=`session:{sessionId}`，value=消息列表（JSON 序列化 domain Message）。List/Hash 结构存。
- **序列化**：domain Message 是 sealed（UserMessage/AssistantMessage/ToolCallMessage/ToolResultMessage），需 polymorphic 序列化（Jackson @JsonTypeInfo/@JsonSubTypes）——**但这在 infra 层**，不污染 domain。infra 用 Jackson 序列化 Message 列表为 JSON 存 Redis。
- **TTL 24h 滑动续期**：load/save 时 expire（滑动）。
- **findByUser**：Redis 不擅长按 user 检索——需维护 `user:{tenant}:{userId}` 的 Set（SessionId 集合），findByUser 遍历该 Set 取 Session 元信息。或一期 findByUser 走 Redis scan（低效但简单）——**一期用 user Set 索引**。
- **多租户**：key 含 tenantId 前缀防串；或 Redis namespace（一期用 key 前缀 `session:{tenant}:{sessionId}`）。

## 4. 条件装配（InfraPersistenceAutoConfiguration）

```java
@Configuration
public class InfraPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    @ConditionalOnProperty(name = "redis.addr", matchIfMissing = true)  // 无 redis.addr 用 InMemory
    public SessionRepository inMemorySessionRepository() { return new InMemorySessionRepository(); }

    @Bean
    @ConditionalOnProperty(name = "redis.addr")
    public SessionRepository redisSessionRepository(RedisTemplate/StringRedisTemplate ...) { ... }
}
```
> 实际两个 @ConditionalOnProperty 互补（有 redis.addr → Redis；无 → InMemory）。无 SessionRepository 时编排层注入 Optional（编排 change 处理）。

## 5. Message 序列化（infra-persistence 内部）

domain `Message` sealed 多态序列化，infra 用 Jackson：
```java
@JsonTypeInfo(use = NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ToolCallMessage.class, name = "tool_call"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "tool_result")
})
```
**问题**：domain Message 是 package-private record（在 `Message.java` 同文件），Jackson 需 public + 无参/record 构造。record 可序列化，但 sealed 子类型 package-private 可能影响 Jackson 反射。**实现期验证**（写序列化测试）。若 Jackson 对 package-private sealed record 不友好，方案 B：infra 定义自己的 DTO（SessionMessage），mapper 双向转换（domain Message ↔ DTO）——更干净，domain 完全不感知序列化。**倾向方案 B**（domain 不被 Jackson 注解污染，保持零框架）。

## 6. 不做（YAGNI）
- 关系 DB 冷持久（二期）。
- 冷会话回源（依赖 DB）。
- 编排逻辑、SSE 端点、认证、限流（编排 change）。
- 会话搜索/标签（后续）。

## 7. 与 domain 端口对接约束
新增 SessionRepository 端口在 domain（零框架）；infra-persistence 实现它（Redis/Jackson 在 infra）。domain Message 仍 sealed 零框架（序列化用 infra DTO mapper，不污染 domain）。

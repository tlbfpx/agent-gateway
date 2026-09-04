# Design: 编排核心 + SSE 端点（add-orchestration-and-sse）

## 1. ChatOrchestrator 编排循环（核心）

### 1.1 流程（spec §2.1）
```
ChatRequest(sessionId, prompt, model?)
  │
  ├─ Authenticator.authenticate(apiKey) → AuthPrincipal
  ├─ AuthorizationService（后续校验点）
  ├─ SessionRepository.load(sessionId) 或 create（多租户：session.tenant == principal.tenant 校验）
  ├─ ModelSelector.select(principal, session, model) → ModelId（§5.5.4）
  ├─ ContextWindow.fit(session.history) → 裁剪历史
  ├─ 工具集构造：AgentCardPort.snapshot() → AuthorizationService 过滤（principal 能调的）→ ToolDescriptor[]
  ├─ ChatClientPort.sessionFor(modelId, tools) → LlmSession
  │
  ├─ LlmSession.generate(prompt + 裁剪历史, ctx) → Flow.Publisher<LlmEvent>
  │     │
  │     ├─ Delta(content) → 流式输出给用户
  │     ├─ ToolCall(toolName, argsJson)
  │     │     ├─ AuthorizationService.checkInvokeAgent（纵深防御第二点）
  │     │     ├─ AgentCardPort.snapshot 找 endpointUrl
  │     │     ├─ ToolPort.invoke(agentCard, argsJson) → Flow.Publisher<ToolEvent>
  │     │     ├─ 收集 ToolEvent → ToolResult
  │     │     └─ 结果回填：session.append(ToolCallMessage + ToolResultMessage)
  │     │           → 下一轮 LlmSession.generate（带工具结果）← 循环
  │     └─ Complete → 结束本轮
  │
  ├─ 持久化：SessionRepository.save（用户消息 + assistant + tool_call + tool_result）
  └─ 流式输出 done event
```

### 1.2 工具调用循环的实现（关键难点）

LlmSession.generate 是「单次 prompt → Flow<LlmEvent>」。ToolCall 事件意味着 LLM 要调工具。
**循环**：generate → 收到 ToolCall → 执行 ToolPort → 收集结果 → **再 generate**（把工具结果作为新 prompt 上下文）→ 直到 Complete（无更多 ToolCall）。

**一期串行**：一轮内多个 ToolCall 顺序执行（不全并行，YAGNI）。
**防死循环**：toolCall 循环上限（如 10 次），超限 Error。

**流式透传**：每个 Delta 实时输出；ToolCall 时不输出（内部执行）；结果回填后再 generate 的新 Delta 继续输出。整个对话是一个连续的 Flow<ChatStreamEvent>。

### 1.3 ChatStreamEvent（编排对外事件，区别于 LlmEvent/ToolEvent）
```java
sealed interface ChatStreamEvent permits Delta, ToolCallStarted, ToolCallResult, Complete, Error {
    record Delta(String content) implements ChatStreamEvent {}                    // LLM 文本增量
    record ToolCallStarted(String agentName) implements ChatStreamEvent {}        // 工具调用开始（前端可显示"调用X中"）
    record ToolCallResult(String agentName, boolean success) implements ChatStreamEvent {}  // 工具结果
    record Complete(String fullText) implements ChatStreamEvent {}                // 本轮完成
    record Error(String code, String message) implements ChatStreamEvent {}       // 错误
}
```
> SSE 端点把 ChatStreamEvent 映射为 SSE event（§8.2）。Delta→chunk, Complete→done, Error→error。

## 2. ModelSelector（§5.5.4）
```java
ModelId select(AuthPrincipal principal, Session session, Optional<ModelId> requested) {
    // ① 请求级 requested（仅本次）② 会话 session.model ③ 默认（配置 gateway.llm.default-model）
    // 校验 AuthorizationService.canUseModel（模型级 RBAC）
}
```

## 3. SSE 端点（gateway-interfaces）
- `POST /v1/chat/stream`：返回 `SseEmitter` / `Flux<ServerSentEvent>`（WebFlux）。
- ChatRequest body（JSON）：`{sessionId?, prompt, model?}` + Header `X-API-Key`。
- 认证 filter：每个请求 authenticate。
- 异常映射：AuthenticationException→401, AuthorizationException→403, ModelNotFound→400。

## 4. 流式一致性（§5.4）
- 用户消息：编排开始时 append + 不立即 save（流结束后统一 save）。
- assistant/tool：流结束后 append + save。
- 中断：异常时不 save assistant（或 save aborted 标记）。

## 5. 与 domain 端口对接约束
编排用 domain 端口（ChatClientPort/ToolPort/AgentCardPort/SessionRepository/Authenticator/AuthorizationService），不直接依赖 infra 实现。ChatStreamEvent 是 application 层的新类型（编排对外契约）。

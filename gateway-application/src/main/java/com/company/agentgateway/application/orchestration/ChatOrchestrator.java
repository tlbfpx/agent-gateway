package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.MultiTenantAuthenticator;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RateLimiter;
import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.iam.AuthenticationException;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.LlmEvent;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.session.AssistantMessage;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.session.ToolCallMessage;
import com.company.agentgateway.domain.session.ToolResultMessage;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排核心（spec §2.1）。把 domain 端口串成端到端流式会话。
 *
 * <p>编排循环：认证 → 加载/创建会话 → 模型选择 → 构造工具集 → LLM 生成 →
 * 若 ToolCall 则调 Agent（纵深防御校验）→ 结果回填 → 再生成 → 流式透传 → 持久化。
 *
 * <p>一期：串行多 tool_call（一轮内顺序执行）；工具循环上限防死循环；流式经 Reactor Flux。
 *
 * <p>工具调用循环：LlmSession.generate 是「单次 prompt → Flow<LlmEvent>」。
 * 收到 ToolCall 后执行 ToolPort、收集结果、把 ToolResult 作为上下文再 generate，直到 Complete。
 */
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private static final int MAX_TOOL_ROUNDS = 10;

    private final Authenticator authenticator;
    private final SessionRepository sessionRepository;
    private final AgentCardPort agentCardPort;
    private final ChatClientPort chatClientPort;
    private final ToolPort toolPort;
    private final AuthorizationService authorizationService;
    private final ObservabilityHooks observabilityHooks;
    private final HistoryPolicy historyPolicy;
    private final com.company.agentgateway.domain.observability.OutputSanitizer outputSanitizer;
    private final RateLimiter rateLimiter;
    private final AuditRepository auditRepository;
    private GatewayEvents events = GatewayEvents.NOOP; // 装配期可注入（see setEvents）
    /** 可选：超限降级策略（Budget.overLimitAction=DOWNGRADE 时装配，see setBudgetDowngradePolicy）。 */
    private com.company.agentgateway.application.billing.BudgetDowngradePolicy budgetDowngradePolicy;
    /** 可选：提示缓存端口（gateway.llm.prompt-cache.enabled=true 时装配，see setPromptCache）。 */
    private com.company.agentgateway.domain.orchestration.PromptCachePort promptCache;

    /** Sprint 4 P0:语义缓存(L1 精确 + L2 向量召回)。可选注入。 */
    private com.company.agentgateway.domain.cache.SemanticCacheFacade semanticCache;

    /** Sprint 2 P1:replay safe 模式(只跳过 mutating tool);null = 关闭。 */
    private java.util.concurrent.atomic.AtomicBoolean safeReplay;

    /** Sprint 2 P2:payload capture helper(可选;注入以捕获 tool_call/tool_result)。 */
    private com.company.agentgateway.domain.replay.PayloadCaptureHelper captureHelper;
    private final ModelId defaultModel;

    public ChatOrchestrator(Authenticator authenticator,
                            SessionRepository sessionRepository,
                            AgentCardPort agentCardPort,
                            ChatClientPort chatClientPort,
                            ToolPort toolPort,
                            AuthorizationService authorizationService,
                            ObservabilityHooks observabilityHooks,
                            ModelId defaultModel) {
        this(authenticator, sessionRepository, agentCardPort, chatClientPort, toolPort,
                authorizationService, RateLimiter.NOOP, null, ObservabilityHooks.NOOP,
                new LastNHistoryPolicy(40),
                com.company.agentgateway.domain.observability.OutputSanitizer.NOOP, defaultModel);
    }

    
    public ChatOrchestrator(Authenticator authenticator,
                            SessionRepository sessionRepository,
                            AgentCardPort agentCardPort,
                            ChatClientPort chatClientPort,
                            ToolPort toolPort,
                            AuthorizationService authorizationService,
                            RateLimiter rateLimiter,
                            AuditRepository auditRepository,
                            ObservabilityHooks observabilityHooks,
                            HistoryPolicy historyPolicy,
                            com.company.agentgateway.domain.observability.OutputSanitizer outputSanitizer,
                            ModelId defaultModel) {
        this.authenticator = authenticator;
        this.sessionRepository = sessionRepository;
        this.agentCardPort = agentCardPort;
        this.chatClientPort = chatClientPort;
        this.toolPort = toolPort;
        this.authorizationService = authorizationService;
        this.rateLimiter = rateLimiter != null ? rateLimiter : RateLimiter.NOOP;
        this.auditRepository = auditRepository;
        this.observabilityHooks = observabilityHooks != null ? observabilityHooks : ObservabilityHooks.NOOP;
        this.historyPolicy = historyPolicy != null ? historyPolicy : new LastNHistoryPolicy(40);
        this.outputSanitizer = outputSanitizer != null ? outputSanitizer
                : com.company.agentgateway.domain.observability.OutputSanitizer.NOOP;
        this.defaultModel = defaultModel;
    }

    /**
     * 编排对话，返回流式事件（Reactor Flux，供 SSE 端点订阅）。
     *
     * @param request    对话请求
     * @param apiKey     调用方 API Key（认证）
     */
    public Flux<ChatStreamEvent> orchestrate(ChatRequest request, String apiKey) {
        return orchestrate(request, apiKey, null);
    }

    /** 带租户选择头（X-Tenant-Id；null=主租户，spec §6.2 二期）。 */
    public Flux<ChatStreamEvent> orchestrate(ChatRequest request, String apiKey, String tenantIdHeader) {
        Many<ChatStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Thread.startVirtualThread(() -> run(request, apiKey, tenantIdHeader, sink));
        return sink.asFlux();
    }

    /** 编排主逻辑（在虚拟线程中运行，向 sink 推事件）。 */
    private void run(ChatRequest request, String apiKey, String tenantIdHeader, Many<ChatStreamEvent> sink) {
        long startTime = System.currentTimeMillis();
        try {
            // Sprint 2 P0:入口请求 capture 移到编排层统一管理
            // (原 ChatController.stream() 入口 captureRequest 移至此,避免 ChatController 依赖
            // PayloadCaptureHelper bean——该 bean 在 Spring 4.0 严格模式下未注册会导致 bean 装配失败)。
            // traceId 暂用占位符("trace-orchestrator"),与下方 captureToolCall/captureToolResult 一致;
            // 真实 OT traceId 通过 Span.current() 透传留给 Round 9 处理。
            if (captureHelper != null) {
                captureHelper.captureRequest("trace-orchestrator", request.prompt(),
                        request.modelOpt().map(m -> m.value()).orElse(null));
            }
            // Round 9:Guardrails 输入检查(GW-GRD-009)
            // - BLOCK 模式:命中违规 → emitError + 立即返回
            // - OBSERVE/REDACT 模式:继续(违规仅记录或脱敏 prompt)
            if (guardrailFacade != null) {
                var inputDecision = guardrailFacade.checkInput(request.prompt(),
                        tenantIdHeader, "trace-orchestrator");
                if (!inputDecision.allowed()) {
                    audit(tenantIdHeader != null ? tenantIdHeader : "primary", "unknown",
                            AuditEventType.SESSION_CHAT, "guardrail", "input-blocked", false,
                            inputDecision.violations().stream()
                                    .map(v -> v.rule().name()).reduce((a, b) -> a + "," + b).orElse(""));
                    emitError(sink, "GUARDRAIL_BLOCKED",
                            "input blocked by guardrail: " + inputDecision.violations().get(0).rule());
                    return;
                }
            }
            // 防御式校验：prompt 缺失直接以 VALIDATION 错误结束，避免下方 length() NPE 变成 ORCHESTRATION_ERROR
            if (request == null || request.prompt() == null || request.prompt().isBlank()) {
                emitError(sink, "VALIDATION_ERROR", "prompt is required and must not be blank");
                return;
            }
            AuthPrincipal principal = (authenticator instanceof MultiTenantAuthenticator mta)
                    ? mta.authenticate(apiKey, tenantIdHeader)
                    : authenticator.authenticate(apiKey);
            audit(principal.tenant().value(), principal.user().value(),
                    AuditEventType.SESSION_CHAT, "session", "chat-start", true, null);
            String limited = rateLimiter.tryAcquire(principal, apiKey, null);
            if (limited != null) {
                audit(principal.tenant().value(), principal.user().value(),
                        AuditEventType.RATE_LIMIT_EXCEEDED, "ratelimit", "acquire", false, limited);
                events.publish("rate.limit.exceeded", java.util.Map.of(
                        "tenant", principal.tenant().value(), "dimension", limited));
                observabilityHooks.onError(principal.tenant().value(), "RATE_LIMITED:" + limited);
                emitError(sink, "RATE_LIMITED", "Rate limit exceeded: " + limited);
                return;
            }
            // token 日预算预扣（粗估 prompt/4 + 预留输出 512；精确 tokenizer 二期）。
            // 拒绝时同样 429 语义（spec §21.4 token 预算 → 拒绝新请求）。
            long estimated = request.prompt().length() / 4 + 512;
            // 超限降级分支（P1）：Budget.overLimitAction=DOWNGRADE 且配置了 fallbackModel 时，
            // 配额超限降级到 fallbackModel 而非直接 429（仅当原模型非 fallback）。
            ModelId downgradedTo = null;
            if (!rateLimiter.tryAcquireTokens(principal.tenant(), estimated)) {
                downgradedTo = tryDowngrade(principal, request);
                if (downgradedTo == null) {
                    observabilityHooks.onError(principal.tenant().value(), "RATE_LIMITED:token-budget");
                    emitError(sink, "RATE_LIMITED", "Daily token budget exceeded");
                    return;
                }
            }
            observabilityHooks.onChatRequest(principal.tenant().value(), principal.user().value(),
                    request.modelOpt().map(ModelId::value).orElse(defaultModel.value()),
                    principal.channel().name());

            // 加载或创建会话（多租户校验）
            Session session = loadOrCreateSession(request, principal);

            // 模型选择（超限降级时直接使用 fallbackModel，仍过 RBAC 校验）
            ModelId modelId;
            if (downgradedTo != null) {
                authorizationService.checkUseModel(principal, downgradedTo);
                modelId = downgradedTo;
                events.publish("budget.downgrade", java.util.Map.of(
                        "tenant", principal.tenant().value(),
                        "from", request.modelOpt().map(ModelId::value).orElse(defaultModel.value()),
                        "to", downgradedTo.value()));
            } else {
                modelId = selectModel(request, session, principal);
            }

            // 提示缓存可缓存条件（保守）：会话此前无多轮 history（首轮请求）
            boolean historyEmpty = session.history().isEmpty();

            // 追加用户消息
            session = session.append(new UserMessage(request.prompt()));

            // 构造工具集（AgentCard → 按 RBAC 过滤 → ToolDescriptor）
            List<ToolDescriptor> tools = buildTools(principal);

            // 提示缓存（语义缓存第一步：规范化精确匹配）：仅「无工具 + 无多轮 history」参与
            String cacheKey = null;
            if ((semanticCache != null || promptCache != null) && tools.isEmpty() && historyEmpty) {
                cacheKey = promptCacheKey(modelId, request.prompt());
            }
            boolean cacheHit = false;
            String fullText;
            // Sprint 4 P0:先走语义缓存(L1 + L2),命中即跳过 LLM
            com.company.agentgateway.domain.cache.CacheLookupResult semanticResult = null;
            if (semanticCache != null && tools.isEmpty() && historyEmpty) {
                String toolsSig = tools.isEmpty() ? "none" : String.valueOf(tools.size());
                semanticResult = semanticCache.lookup(
                        principal.tenant().value(), modelId.value(),
                        request.prompt(), toolsSig, 0);
            }
            if (semanticResult != null && semanticResult.isHit()) {
                cacheHit = true;
                fullText = semanticResult.responseBody();
                sink.tryEmitNext(new ChatStreamEvent.Delta(outputSanitizer.sanitize(fullText)));
                events.publish("cache.hit", java.util.Map.of(
                        "tenant", principal.tenant().value(),
                        "model", modelId.value(),
                        "kind", semanticResult.kind().name(),
                        "similarity", semanticResult.similarity()));
            } else if (cacheKey != null) {
                var cached = promptCache.get(cacheKey);
                if (cached.isPresent()) {
                    cacheHit = true;
                    fullText = cached.get().answer();
                    sink.tryEmitNext(new ChatStreamEvent.Delta(outputSanitizer.sanitize(fullText)));
                } else {
                    fullText = runToolLoop(session, principal, modelId, tools, request.prompt(), sink);
                    if (!fullText.isBlank()) {
                        promptCache.put(cacheKey, new com.company.agentgateway.domain.orchestration.PromptCachePort.CacheEntry(
                                fullText, java.time.Instant.now(), modelId.value()));
                    }
                }
            } else {
                // 工具循环
                fullText = runToolLoop(session, principal, modelId, tools, request.prompt(), sink);
            }

            // 持久化
            Session finalSession = session.append(new AssistantMessage(fullText));
            sessionRepository.save(finalSession);

            observabilityHooks.onChatComplete(principal.tenant().value(), modelId.value(),
                    System.currentTimeMillis() - startTime, true);
            // 消息级透明（估算口径 chars/4，与限流预扣一致）：实际命中模型 + token 用量
            // 异步写缓存(非命中路径,且无工具 + 无多轮)
            if (!cacheHit && !fullText.isBlank() && tools.isEmpty() && historyEmpty && semanticCache != null) {
                String toolsSig = tools.isEmpty() ? "none" : String.valueOf(tools.size());
                semanticCache.writeAsync(
                        principal.tenant().value(), modelId.value(),
                        request.prompt(), fullText,
                        request.prompt().length() / 4, fullText.length() / 4,
                        0.01, // costSavedCents — 真实值由 quota 计费模块补充
                        toolsSig, 0);
            }

            sink.tryEmitNext(new ChatStreamEvent.Complete(fullText, new ChatStreamEvent.Meta(
                    modelId.value(),
                    (request.prompt().length() + fullText.length()) / 4,
                    fullText.length() / 4,
                    cacheHit)));
            sink.tryEmitComplete();
        } catch (AuthenticationException e) {
            audit("unknown", "anonymous", AuditEventType.AUTH_FAILED, "apikey", "authenticate", false, e.getMessage());
            observabilityHooks.onError("unknown", "AUTH_ERROR");
            emitError(sink, "AUTH_ERROR", e.getMessage());
        } catch (AuthorizationException e) {
            audit("unknown", "unknown", AuditEventType.RBAC_DENIED, "rbac", "authorize", false, e.getMessage());
            observabilityHooks.onChatComplete("unknown", "unknown",
                    System.currentTimeMillis() - startTime, false);
            observabilityHooks.onError("unknown", "AUTHORIZATION_ERROR");
            emitError(sink, "AUTHORIZATION_ERROR", e.getMessage());
        } catch (Exception e) {
            log.error("Orchestration failed", e);
            observabilityHooks.onError("unknown", "ORCHESTRATION_ERROR");
            emitError(sink, "ORCHESTRATION_ERROR", e.getMessage());
        }
    }

    /** 工具调用循环：generate → 若 ToolCall 则调 Agent → 回填 → 再 generate，直到 Complete。 */
    private String runToolLoop(Session session, AuthPrincipal principal, ModelId modelId,
                               List<ToolDescriptor> tools, String prompt, Many<ChatStreamEvent> sink) {
        StringBuilder fullText = new StringBuilder();
        String currentPrompt = prompt;
        List<com.company.agentgateway.domain.session.Message> context =
                new ArrayList<>(session.history());
        AtomicInteger round = new AtomicInteger(0);
        InvocationCtx ctx = new InvocationCtx(session.id(), principal, "trace-orchestrator");

        while (round.get() < MAX_TOOL_ROUNDS) {
            round.incrementAndGet();
            // 每轮（重新）获取 LlmSession（配置可能变更；且测试需区分轮次）
            LlmSession llmSession = chatClientPort.sessionFor(modelId, tools);
            // 收集本轮 LlmEvent
            List<com.company.agentgateway.domain.session.Message> history =
                    historyPolicy.assemble(session.history(), context);
            List<LlmEvent> events = collectLlmEvents(llmSession.generate(currentPrompt, history, ctx));
            StringBuilder deltaText = new StringBuilder();
            List<LlmEvent.ToolCall> toolCalls = new ArrayList<>();
            boolean complete = false;

            for (LlmEvent event : events) {
                if (event instanceof LlmEvent.Delta d) {
                    deltaText.append(d.content());
                    sink.tryEmitNext(new ChatStreamEvent.Delta(outputSanitizer.sanitize(d.content())));
                } else if (event instanceof LlmEvent.ToolCall tc) {
                    toolCalls.add(tc);
                } else if (event instanceof LlmEvent.Complete) {
                    complete = true;
                }
            }
            fullText.append(deltaText);

            // 无 tool_call → 结束（complete 或无更多）
            if (toolCalls.isEmpty()) {
                break;
            }
            // 执行 tool_call（串行），结果回填上下文
            currentPrompt = executeToolCalls(toolCalls, context, principal, ctx, sink, deltaText.toString());
        }

        if (round.get() >= MAX_TOOL_ROUNDS) {
            emitError(sink, "TOO_MANY_TOOL_ROUNDS", "Exceeded max tool call rounds: " + MAX_TOOL_ROUNDS);
        }
        return fullText.toString();
    }

    /** 串行执行 tool_calls，返回「下一轮 prompt」（含工具结果的上下文）。 */
    private String executeToolCalls(List<LlmEvent.ToolCall> toolCalls,
                                    List<com.company.agentgateway.domain.session.Message> context,
                                    AuthPrincipal principal, InvocationCtx ctx,
                                    Many<ChatStreamEvent> sink, String lastDelta) {
        StringBuilder nextPrompt = new StringBuilder(lastDelta);
        for (LlmEvent.ToolCall tc : toolCalls) {
            sink.tryEmitNext(new ChatStreamEvent.ToolCallStarted(tc.toolName()));
            long agentStart = System.currentTimeMillis();
            observabilityHooks.onAgentInvoke(principal.tenant().value(), tc.toolName(), ctx.session().value());
            // 纵深防御：A2A 调用前二次校验
            authorizationService.checkInvokeAgent(principal, tc.toolName());

            // Sprint 2 P1:mutating 工具跳过(Safe Replay)
            Optional<AgentCard> _card = agentCardPort.snapshot().stream()
                    .filter(a -> a.name().equals(tc.toolName()))
                    .findFirst();
            if (_card.isPresent() && _card.get().mutating() && safeReplay != null && safeReplay.get()) {
                sink.tryEmitNext(new ChatStreamEvent.ToolCallResult(tc.toolName(), false));
                events.publish("replay.mutating_skipped", java.util.Map.of(
                        "tenant", principal.tenant().value(),
                        "tool", tc.toolName(),
                        "reason", "safe_replay"));
                nextPrompt.append("\n\n[Tool " + tc.toolName() + " skipped by safe replay]\n");
                continue;
            }

            // 找 AgentCard（取 endpointUrl）
            Optional<AgentCard> card = agentCardPort.snapshot().stream()
                    .filter(a -> a.name().equals(tc.toolName()))
                    .findFirst();
            String result;
            boolean success;
            // Sprint 2 P2:捕获 tool_call
            if (captureHelper != null) {
                captureHelper.captureToolCall(ctx.traceId() == null ? "unknown" : ctx.traceId(),
                        "", tc.toolName(), tc.argsJson());
            }
            if (card.isEmpty()) {
                result = "Agent not found: " + tc.toolName();
                success = false;
            } else {
                result = invokeTool(card.get(), tc.argsJson(), ctx);
                success = !result.startsWith("ERROR:");
            }
            // Sprint 2 P2:捕获 tool_result
            if (captureHelper != null) {
                captureHelper.captureToolResult(ctx.traceId() == null ? "unknown" : ctx.traceId(),
                        "", tc.toolName(), result);
            }
            observabilityHooks.onAgentComplete(principal.tenant().value(), tc.toolName(),
                    System.currentTimeMillis() - agentStart, success);
            events.publish("agent.invoked", java.util.Map.of(
                    "tenant", principal.tenant().value(),
                    "agent", tc.toolName(),
                    "success", success));
            audit(principal.tenant().value(), principal.user().value(),
                    AuditEventType.AGENT_REGISTER, tc.toolName(), "invoke", success, null);
            sink.tryEmitNext(new ChatStreamEvent.ToolCallResult(tc.toolName(), success));
            context.add(new ToolCallMessage(tc.toolName(), tc.argsJson()));
            // toolCallId(spec C1 §3.3):非空 → 原生 ToolResponseMessage 回填(OpenAI 协议);
            // 为空 → 文本降级(全厂商兜底)。
            context.add(new ToolResultMessage(tc.toolName(), result, false, tc.toolCallId()));
            nextPrompt.append("\n[ToolResult:").append(tc.toolName()).append("] ").append(result);
        }
        return nextPrompt.toString();
    }

    /** 调用 ToolPort，收集所有 ToolEvent，返回完整结果文本。 */
    private String invokeTool(AgentCard card, String argsJson, InvocationCtx ctx) {
        StringBuilder result = new StringBuilder();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        toolPort.invoke(card, argsJson, ctx).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override
            public void onNext(ToolEvent event) {
                if (event instanceof ToolEvent.Delta d) {
                    result.append(d.content());
                } else if (event instanceof ToolEvent.Complete c) {
                    if (result.isEmpty()) {
                        result.append(c.fullResult());
                    }
                } else if (event instanceof ToolEvent.Error e) {
                    errorRef.set(e.code() + ": " + e.message());
                }
            }
            @Override
            public void onError(Throwable t) { errorRef.set(t.getMessage()); latch.countDown(); }
            @Override
            public void onComplete() { latch.countDown(); }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted";
        }
        return errorRef.get() != null ? "ERROR:" + errorRef.get() : result.toString();
    }

    /** 同步收集 LlmSession.generate 的 Flow<LlmEvent>（阻塞至完成）。 */
    private List<LlmEvent> collectLlmEvents(java.util.concurrent.Flow.Publisher<LlmEvent> publisher) {
        List<LlmEvent> events = new ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override
            public void onNext(LlmEvent event) { events.add(event); }
            @Override
            public void onError(Throwable t) { errorRef.set(t); latch.countDown(); }
            @Override
            public void onComplete() { latch.countDown(); }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted collecting LLM events", e);
        }
        if (errorRef.get() != null) {
            throw new RuntimeException("LLM generation failed", errorRef.get());
        }
        return events;
    }

    private Session loadOrCreateSession(ChatRequest request, AuthPrincipal principal) {
        if (request.sessionId() != null) {
            Session session = sessionRepository.load(request.sessionId());
            if (session == null) {
                throw new IllegalArgumentException("Session not found: " + request.sessionId());
            }
            // 多租户校验
            if (!session.tenant().equals(principal.tenant())) {
                throw new AuthorizationException("Session does not belong to tenant: " + principal.tenant());
            }
            return session;
        }
        return sessionRepository.create(principal.tenant(), principal.user(), defaultModel);
    }

    /** 超限降级查询：策略可用且租户配置 DOWNGRADE 时返回 fallbackModel，否则 null（维持拒绝）。 */
    private ModelId tryDowngrade(AuthPrincipal principal, ChatRequest request) {
        if (budgetDowngradePolicy == null) return null;
        ModelId requested = request.modelOpt()
                .orElseGet(() -> Optional.ofNullable(sessionRepositoryLoadModel(request)).orElse(defaultModel));
        return budgetDowngradePolicy.downgradeModelFor(principal.tenant(), requested).orElse(null);
    }

    /** 会话绑定模型（无会话时 null）。 */
    private ModelId sessionRepositoryLoadModel(ChatRequest request) {
        if (request.sessionId() == null) return null;
        try {
            Session s = sessionRepository.load(request.sessionId());
            return s != null ? s.model() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ModelId selectModel(ChatRequest request, Session session, AuthPrincipal principal) {
        ModelId modelId = request.modelOpt()
                .orElseGet(() -> Optional.ofNullable(session.model()).orElse(defaultModel));
        authorizationService.checkUseModel(principal, modelId);
        return modelId;
    }

    private List<ToolDescriptor> buildTools(AuthPrincipal principal) {
        return agentCardPort.snapshot().stream()
                .filter(card -> authorizationService.canInvokeAgent(principal, card.name()))
                // Skill 级 RBAC（spec §6.3 二期）：grant 非空时只保留授权 skills；全滤空的 Agent 不注入
                .map(card -> toolWithSkillScope(principal, card))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 工具描述注入授权 skill 范围（LLM 感知边界）；非空 grant 滤空 skills 则返回 null（不注入）。 */
    private ToolDescriptor toolWithSkillScope(AuthPrincipal principal,
                                             com.company.agentgateway.domain.registry.AgentCard card) {
        var grantOpt = principal.agentGrants().stream()
                .filter(g -> g.agentName().equals(card.name())).findFirst();
        if (grantOpt.isEmpty()) {
            return new ToolDescriptor(card.name(), card.description(), card.inputSchema(), card.mutating());
        }
        var grant = grantOpt.get();
        var allowed = grant.filterSkills(card.skills());
        if (!card.skills().isEmpty() && allowed.isEmpty()) {
            return null; // 有 skills 但全被滤掉 → 隐藏该 Agent
        }
        String desc = card.description();
        if (!allowed.isEmpty()) {
            desc = desc + " [可用能力: " + String.join(", ", allowed) + "]";
        }
        return new ToolDescriptor(card.name(), desc, card.inputSchema(), card.mutating());
    }

    /** 装配期注入事件端口（可选，缺省 NOOP）。仅 Spring 装配时调用。 */
    public void setEvents(GatewayEvents events) {
        this.events = events != null ? events : GatewayEvents.NOOP;
    }

    /** 装配期注入提示缓存端口（可选；gateway.llm.prompt-cache.enabled=true 时装配）。 */
    public void setPromptCache(com.company.agentgateway.domain.orchestration.PromptCachePort promptCache) {
        this.promptCache = promptCache;
    }

    /** Sprint 4 P0:装配期注入语义缓存服务(可选)。 */
    public void setSemanticCache(com.company.agentgateway.domain.cache.SemanticCacheFacade semanticCache) {
        this.semanticCache = semanticCache;
    }

    /**
     * Sprint 2 P1:启用/禁用 replay-safe mode。
     * true 时 executeToolCalls 跳过 AgentCard.mutating=true 的工具。
     * 每个请求独立设置(orchestrator 单实例可服务多请求)。
     */
    public void setSafeReplay(boolean safeReplay) {
        this.safeReplay = new java.util.concurrent.atomic.AtomicBoolean(safeReplay);
    }

    /** Sprint 2 P2:装配期注入 capture helper(可选)。 */
    public void setCaptureHelper(com.company.agentgateway.domain.replay.PayloadCaptureHelper captureHelper) {
        this.captureHelper = captureHelper;
    }

    /** Round 9:装配期注入 guardrail facade(可选,无则不做安全检查)。 */
    public void setGuardrailFacade(com.company.agentgateway.domain.safety.GuardrailFacade guardrailFacade) {
        this.guardrailFacade = guardrailFacade;
    }
    private com.company.agentgateway.domain.safety.GuardrailFacade guardrailFacade;

    /**
     * 缓存键：SHA-256(模型ID + 规范化 prompt + 采样参数 temperature)。
     * 规范化：trim + 连续空白折叠为单空格（与 LiteLLM/Portkey prompt-cache 同思路，避免排版差异漏命中）。
     * 当前请求链路未携带 temperature（走模型默认值），固定占位 "temp=default"，
     * 未来 ChatRequest 增加 temperature 时直接替换该段即可。
     */
    static String promptCacheKey(ModelId modelId, String prompt) {
        String normalized = prompt == null ? "" : prompt.strip().replaceAll("\\s+", " ");
        String material = modelId.value() + "\n" + normalized + "\ntemp=default";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public void setBudgetDowngradePolicy(
            com.company.agentgateway.application.billing.BudgetDowngradePolicy policy) {
        this.budgetDowngradePolicy = policy;
    }

    /** 审计埋点（auditRepository 可能为 null——审计不阻塞主链路）。 */
    private void audit(String tenant, String actor, AuditEventType type, String resource,
                       String action, boolean success, String detail) {
        if (auditRepository == null) return;
        try {
            auditRepository.append(new AuditLog(
                    java.util.UUID.randomUUID().toString(),
                    new com.company.agentgateway.domain.shared.TenantId(tenant),
                    actor, AuditLog.ActorType.SERVICE, type, java.time.Instant.now(),
                    resource, resource, action,
                    success ? AuditLog.Result.SUCCESS : AuditLog.Result.FAILURE,
                    detail));
        } catch (Exception e) {
            log.warn("audit append failed: {}", e.getMessage());
        }
    }

    private void emitError(Many<ChatStreamEvent> sink, String code, String message) {
        sink.tryEmitNext(new ChatStreamEvent.Error(code, message));
        sink.tryEmitComplete();
    }
}

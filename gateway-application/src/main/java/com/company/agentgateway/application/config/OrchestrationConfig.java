package com.company.agentgateway.application.config;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.replay.ReplayService;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.shared.ModelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 ChatOrchestrator（编排核心）。依赖的端口由各 infra 模块的 autoconfiguration 提供。
 * ObservabilityHooks 可选注入（无 Micrometer 时用 NOOP）。
 */
@Configuration
public class OrchestrationConfig {

    @Bean
    public ChatOrchestrator chatOrchestrator(
            Authenticator authenticator,
            SessionRepository sessionRepository,
            AgentCardPort agentCardPort,
            ChatClientPort chatClientPort,
            ToolPort toolPort,
            AuthorizationService authorizationService,
            @Autowired(required = false) com.company.agentgateway.domain.iam.RateLimiter rateLimiter,
            @Autowired(required = false) com.company.agentgateway.domain.audit.AuditRepository auditRepository,
            @Autowired(required = false) com.company.agentgateway.domain.observability.GatewayEvents events,
            @Autowired(required = false) ObservabilityHooks observabilityHooks,
            @Autowired(required = false)
            com.company.agentgateway.application.billing.BudgetDowngradePolicy budgetDowngradePolicy,
            // 提示缓存端口可选（gateway.llm.prompt-cache.enabled=true 时由 infra-llm 装配）
            @Autowired(required = false)
            com.company.agentgateway.domain.orchestration.PromptCachePort promptCache,
            @Value("${gateway.llm.default-model:qwen-max}") String defaultModel,
            @Value("${gateway.llm.history.max-messages:40}") int maxHistoryMessages,
            @Value("${gateway.llm.history.policy:last-n}") String historyPolicyName,
            @Value("${gateway.llm.pii-filter.enabled:false}") boolean piiFilter) {
        com.company.agentgateway.application.orchestration.HistoryPolicy historyPolicy;
        if ("summarizing".equalsIgnoreCase(historyPolicyName)) {
            // 摘要器：规则式（拼接压缩）——小模型摘要可后续替换（保持策略纯逻辑）
            historyPolicy = new com.company.agentgateway.application.orchestration.SummarizingHistoryPolicy(
                    maxHistoryMessages, maxHistoryMessages * 2,
                    list -> "[对话摘要] " + list.size() + " 条早前消息");
        } else {
            historyPolicy = new com.company.agentgateway.application.orchestration.LastNHistoryPolicy(maxHistoryMessages);
        }
        var sanitizer = piiFilter
                ? new com.company.agentgateway.application.observability.PiiOutputSanitizer()
                : com.company.agentgateway.domain.observability.OutputSanitizer.NOOP;
        var orchestrator = new ChatOrchestrator(authenticator, sessionRepository, agentCardPort,
                chatClientPort, toolPort, authorizationService, rateLimiter, auditRepository,
                observabilityHooks, historyPolicy, sanitizer, new ModelId(defaultModel));
        orchestrator.setEvents(events); // 可选事件端口（Webhook 桥接在 interfaces 层）
        orchestrator.setBudgetDowngradePolicy(budgetDowngradePolicy); // 可选超限降级（P1）
        orchestrator.setPromptCache(promptCache); // 可选提示缓存（gateway.llm.prompt-cache.enabled）
        return orchestrator;
    }

    /**
     * Sprint 2 P0 + Round 8 修复:ReplayService 注册为 bean,
     * 供 AdminReplayController 注入。无 ChatOrchestrator 注入时可降级(返回 dummy)。
     */
    @Bean
    public ReplayService replayService(
            com.company.agentgateway.domain.replay.PayloadCapturePort payloadPort,
            ChatOrchestrator orchestrator,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Autowired(required = false)
            com.company.agentgateway.application.replay.CallbackSigner callbackSigner) {
        return new ReplayService(payloadPort, orchestrator, objectMapper, callbackSigner);
    }

}

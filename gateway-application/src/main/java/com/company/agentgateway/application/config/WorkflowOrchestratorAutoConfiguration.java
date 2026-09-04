package com.company.agentgateway.application.config;

import com.company.agentgateway.application.workflow.JaywayJsonPathResolverAdapter;
import com.company.agentgateway.application.workflow.WorkflowOrchestratorImpl;
import com.company.agentgateway.application.workflow.WorkflowParseService;
import com.company.agentgateway.application.workflow.repo.InMemoryWorkflowDefinitionRepository;
import com.company.agentgateway.application.workflow.repo.InMemoryWorkflowRepository;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.workflow.JsonPathResolver;
import com.company.agentgateway.domain.workflow.WorkflowDefinitionRepository;
import com.company.agentgateway.domain.workflow.WorkflowOrchestrator;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WorkflowOrchestrator 装配(spec C1 §3.4):首期 InMemoryWorkflowRepository + JaywayJsonPathResolverAdapter。
 * gateway.infra 层加 WorkflowRepository 的 PG 实现时,这里改 @ConditionalOnMissingBean 即可。
 */
@Configuration
public class WorkflowOrchestratorAutoConfiguration {

    /** C1 默认装配:WorkflowRepository 来自 InMemoryWorkflowRepository;
     *  启 PG 持久化(spec P1)时 InfraPersistenceAutoConfiguration 配 PgWorkflowRepository(@Primary)。
     *  二选一条件:在 application.yml 显式设 observability.storage.jdbc-url → 配 Pg(并标 @Primary);
     *  不设 jdbc-url → 仅 InMemory(默认 E2E 与单测场景)。
     *  注:ConditionalOnMissingBean 在 factory 解析时不会回退,所以两侧条件需互斥:
     *  InMemory 用 "observability.storage.jdbc-url" 缺省为匹配(不设即装配)。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "observability.storage.enabled", matchIfMissing = true, havingValue = "false")
    public WorkflowRepository inMemoryWorkflowRepository() {
        return new InMemoryWorkflowRepository();
    }

    @Bean
    @ConditionalOnMissingBean(JsonPathResolver.class)
    public JsonPathResolver jsonPathResolver(ObjectMapper objectMapper) {
        return new JaywayJsonPathResolverAdapter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowOrchestrator.class)
    public WorkflowOrchestrator workflowOrchestrator(ToolPort toolPort,
                                                    AgentCardPort agentCardPort,
                                                    ObservabilityHooks observabilityHooks,
                                                    WorkflowRepository repository,
                                                    JsonPathResolver resolver,
                                                    ObjectMapper objectMapper,
                                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                    com.company.agentgateway.domain.observability.GatewayEvents gatewayEvents) {
        return new WorkflowOrchestratorImpl(toolPort, agentCardPort, observabilityHooks,
                repository, resolver, objectMapper, gatewayEvents);
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowParseService.class)
    public WorkflowParseService workflowParseService(ObjectMapper objectMapper) {
        return new WorkflowParseService(objectMapper);
    }

    /** WorkflowDefinitionRepository(C1 §8 扩展):降级 InMemory。 */
    @Bean
    @ConditionalOnMissingBean(WorkflowDefinitionRepository.class)
    public WorkflowDefinitionRepository inMemoryWorkflowDefinitionRepository() {
        return new InMemoryWorkflowDefinitionRepository();
    }
}
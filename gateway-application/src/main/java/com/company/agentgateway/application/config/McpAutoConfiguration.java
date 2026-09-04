package com.company.agentgateway.application.config;

import com.company.agentgateway.application.mcp.McpJsonRpcDispatcher;
import com.company.agentgateway.domain.mcp.McpPort;
import com.company.agentgateway.infra.persistence.mcp.InMemoryMcpServerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 用例层自动装配（Round 14 §mcp）。
 */
@Configuration
public class McpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InMemoryMcpServerRepository.class)
    public InMemoryMcpServerRepository inMemoryMcpServerRepository() {
        return new InMemoryMcpServerRepository();
    }

    @Bean
    @ConditionalOnMissingBean(McpPort.class)
    public McpPort mcpPort(InMemoryMcpServerRepository repo) {
        return repo;
    }

    @Bean
    @ConditionalOnMissingBean(McpJsonRpcDispatcher.class)
    public McpJsonRpcDispatcher mcpJsonRpcDispatcher(McpPort port) {
        return new McpJsonRpcDispatcher(port);
    }
}

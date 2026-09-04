package com.company.agentgateway.infra.nacos.config;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.infra.nacos.NacosAgentCardPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * infra-nacos 运行期装配（条件装配）。
 *
 * <p>配置 nacos.addr 时创建 AiService + AgentCardPort：
 * <ul>
 *   <li>AiService 经 {@link AiFactory#createAiService(Properties)}（nacos-client 3.3.0-BETA）</li>
 *   <li>NacosAgentCardPort 订阅初始 Agent 名集合（gateway.a2a.initial-agents，逗号分隔）</li>
 * </ul>
 * 未配 nacos.addr（开发态/测试）时不装配，应用空启动。真实 Nacos 接入留部署。
 */
@Configuration
@ConditionalOnProperty(name = "nacos.addr")
public class InfraNacosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InfraNacosAutoConfiguration.class);

    @Bean
    public AiService nacosAiService(
            @Value("${nacos.addr}") String serverAddr,
            @Value("${nacos.namespace:}") String namespace,
            @Value("${nacos.username:}") String username,
            @Value("${nacos.password:}") String password) throws NacosException {
        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr);
        if (namespace != null && !namespace.isBlank()) props.setProperty("namespace", namespace);
        if (username != null && !username.isBlank()) props.setProperty("username", username);
        if (password != null && !password.isBlank()) props.setProperty("password", password);
        return AiFactory.createAiService(props);
    }

    @Bean
    public AgentCardPort agentCardPort(
            AiService aiService,
            @Value("${gateway.a2a.initial-agents:}") String initialAgentsCsv) {
        NacosAgentCardPort port = new NacosAgentCardPort(aiService);
        List<String> initial = parseAgentNames(initialAgentsCsv);
        if (!initial.isEmpty()) {
            log.info("Subscribing initial agents: {}", initial);
            port.subscribe(initial);
        }
        return port;
    }

    private List<String> parseAgentNames(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

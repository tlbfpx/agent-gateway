package com.company.agentgateway.exampleagent;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 示例远程 Agent：最小 A2A 兼容服务器（echo-agent）。
 *
 * <p>功能：POST /a2a/invoke/{agentName} 接收 JSON-RPC，返回 SSE 流（chunk × N → done）。
 * echo-agent 回显输入并标记「[echoed by example-agent]」。
 *
 * <p>Nacos 注册：有 nacos.addr 时启动后向 Nacos 注册 AgentCard（AiService.releaseAgentCard），
 * 供网关 AgentCardPort 发现。无 nacos.addr 时不注册（仅本地可调）。
 *
 * <p>用途：端到端联调验证网关编排→ToolPort→A2A→本 Agent 的完整链路。
 */
@SpringBootApplication
public class ExampleAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleAgentApplication.class, args);
    }

    /** 有 nacos.addr 时注册 AgentCard。 */
    @Bean
    @ConditionalOnProperty(name = "nacos.addr")
    public ApplicationRunner registerToNacos(
            @Value("${nacos.addr}") String addr,
            @Value("${server.port:8090}") int port,
            @Value("${example.agent.name:echo-agent}") String agentName) {
        return args -> {
            try {
                Properties props = new Properties();
                props.setProperty("serverAddr", addr);
                AiService aiService = AiFactory.createAiService(props);
                AgentCard card = new AgentCard();
                card.setName(agentName);
                card.setDescription("示例回显 Agent（example-agent），用于端到端联调");
                card.setVersion("1.0.0");
                card.setUrl("http://localhost:" + port + "/a2a/invoke/" + agentName);
                card.setSkills(List.of());
                aiService.releaseAgentCard(card);
                LoggerFactory.getLogger(ExampleAgentApplication.class)
                        .info("Registered AgentCard '{}' to Nacos at {}", agentName, addr);
            } catch (NacosException e) {
                LoggerFactory.getLogger(ExampleAgentApplication.class)
                        .warn("Failed to register AgentCard to Nacos (continue without registration): {}", e.getMessage());
            }
        };
    }

    /** A2A 端点。 */
    @RestController
    static class A2aInvokeController {
        private static final Logger log = LoggerFactory.getLogger(A2aInvokeController.class);

        @PostMapping(value = "/a2a/invoke/{agentName}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter invoke(@RequestBody Map<String, Object> jsonRpc) {
            SseEmitter emitter = new SseEmitter(60_000L);
            Thread.startVirtualThread(() -> {
                try {
                    // 解析 JSON-RPC params（argsJson）
                    Object params = jsonRpc.get("params");
                    String input = params == null ? "(empty)" : params.toString();
                    String echoed = input + " [echoed by example-agent]";
                    // 流式输出：逐字符 chunk + done
                    for (char c : echoed.toCharArray()) {
                        emitter.send(SseEmitter.event().name("chunk").data(String.valueOf(c)));
                        Thread.sleep(10);
                    }
                    emitter.send(SseEmitter.event().name("done").data(echoed));
                    emitter.complete();
                } catch (IOException | InterruptedException e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }
    }
}

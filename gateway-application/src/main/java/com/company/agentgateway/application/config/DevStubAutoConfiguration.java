package com.company.agentgateway.application.config;

import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Dev/Stub 兜底：仅当对应端口 bean 缺失时提供 stub（gateway.dev=true 激活）。
 *
 * <p><b>不提供 ChatClientPort stub</b>——模型接入统一走 infra-llm 的可插拔链路
 * （ChatModelProvider SPI + FileModelRegistry/NacosModelRegistry），保证「配置里的模型
 * 永远真实生效」，不再有 echo 假模型与真实模型抢注的问题。
 *
 * <p>保留的 stub：
 * <ul>
 *   <li>AgentCardPort：固定的 echo-agent 目录（无 Nacos 时前端 Agent 列表可用）</li>
 *   <li>ToolPort：模拟 Agent 调用（无 A2A 环境时编排链路可演示）</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "gateway.dev", havingValue = "true", matchIfMissing = true)
public class DevStubAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentCardPort.class)
    public AgentCardPort stubAgentCardPort() {
        // DevStub echo-agent:多实例列表(spec B §4.3) —— RoundRobin 轮询 + 失败转移
        AgentCard echoCard = new AgentCard("echo-agent", "回显示例 Agent（dev stub）",
                List.of("echo"), "{}", "{}", "1.0.0", true,
                "http://localhost:8090/a2a/invoke/echo-agent",
                List.of("http://localhost:8090/a2a/invoke/echo-agent",
                        "http://localhost:8091/a2a/invoke/echo-agent"));  // 8091 可选,故障转移测试用
        return new AgentCardPort() {
            @Override
            public List<AgentCard> snapshot() { return List.of(echoCard); }
            @Override
            public Flow.Publisher<List<AgentCard>> watch() { return subscriber -> subscriber.onComplete(); }
        };
    }

    /**
     * ToolPort stub 仅在显式关闭 A2A(a2a.enabled=false)时兜底。
     *
     * <p>原因:本类经 component-scan 注册(scanBasePackages 含 application 包),先于
     * infra-a2a 的 auto-config 处理,@ConditionalOnMissingBean 会让 A2A 的 A2aToolPort
     * 永远输 —— 真实 A2A 调用被 stub 劫持。改为与 a2a.enabled 互斥,A2A 默认开启。
     */
    @Bean
    @ConditionalOnProperty(name = "a2a.enabled", havingValue = "false")
    public ToolPort stubToolPort() {
        return (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onNext(new ToolEvent.Delta("[stub result for " + agent.name() + "]"));
                    subscriber.onNext(new ToolEvent.Complete("[stub result]"));
                    subscriber.onComplete();
                }
                @Override
                public void cancel() {}
            });
        };
    }
}

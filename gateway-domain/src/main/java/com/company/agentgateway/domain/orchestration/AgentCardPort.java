package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.registry.AgentCard;
import java.util.List;
import java.util.concurrent.Flow;

/** 出站端口：AgentCard 发现。由 gateway-infra-nacos 实现。snapshot=缓存全量，watch=变更流。 */
public interface AgentCardPort {
    List<AgentCard> snapshot();
    Flow.Publisher<List<AgentCard>> watch();
}

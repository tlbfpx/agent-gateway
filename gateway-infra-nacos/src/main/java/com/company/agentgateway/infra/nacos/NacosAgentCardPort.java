package com.company.agentgateway.infra.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.registry.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;

/**
 * AgentCardPort 实现：基于 nacos-client 内置 A2A AiService 的 AgentCard 发现。
 *
 * <p>机制（spec §4.1，Nacos API 经 javap 核对）：
 * <ul>
 *   <li>推送优先：对已知 Agent 名 {@link AiService#subscribeAgentCard(String, AbstractNacosAgentCardListener)}，
 *       listener.onEvent 收到 {@link NacosAgentCardEvent} 时更新本地缓存 + 广播给 watch() 订阅者</li>
 *   <li>snapshot()：返回当前缓存的全量快照</li>
 *   <li>getByName(name)：主动 {@link AiService#getAgentCard(String)} 拉取单个（编排层按需用）</li>
 * </ul>
 *
 * <p><b>已知限制（Nacos A2A 无 listAll API）</b>：snapshot() 只能返回已订阅/已拉取过的 Agent。
 * 初始订阅的 Agent 名集合由构造期注入（如配置或编排层注册）。
 * 真正的「发现未知 Agent」需编排层配合（如 Nacos 服务列表 + 逐个 getAgentCard），本 Port 不假设。
 *
 * <p>降级（spec §8.4）：Nacos 调用失败时记日志 + 指标，保留上次缓存，不抛异常给调用方。
 */
public class NacosAgentCardPort implements AgentCardPort {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentCardPort.class);

    private final AiService aiService;

    /** 本地缓存：agentName → domain AgentCard（推送/拉取维护） */
    private final Map<String, AgentCard> cache = new ConcurrentHashMap<>();
    /** watch() 的下游订阅者 */
    private final SubmissionPublisher<List<AgentCard>> watchPublisher = new SubmissionPublisher<>();
    /** Nacos 不可达指标回调（可选，由调用方注入；默认空） */
    private final Consumer<String> unreachableCallback;

    public NacosAgentCardPort(AiService aiService) {
        this(aiService, name -> {});
    }

    public NacosAgentCardPort(AiService aiService, Consumer<String> unreachableCallback) {
        this.aiService = aiService;
        this.unreachableCallback = unreachableCallback;
    }

    /**
     * 订阅指定 Agent 名的变更（推送）。对每个 name 注册一个 listener，收到事件时更新缓存 + 广播。
     * 启动期由装配层对已知 Agent 名集合调用。
     */
    public void subscribe(List<String> agentNames) {
        for (String name : agentNames) {
            try {
                aiService.subscribeAgentCard(name, new CardListener(name));
            } catch (NacosException e) {
                log.warn("subscribeAgentCard failed for {} (Nacos 不可达?), 降级保留空缓存: {}", name, e.getMessage());
                unreachableCallback.accept(name);
            }
        }
    }

    @Override
    public List<AgentCard> snapshot() {
        return List.copyOf(cache.values());
    }

    @Override
    public Flow.Publisher<List<AgentCard>> watch() {
        return watchPublisher;
    }

    /**
     * 主动拉取单个 Agent（编排层按需查未知 Agent 时用）。成功则更新缓存 + 广播；失败降级不抛。
     * @return domain AgentCard，或 null（不存在/不可达）
     */
    public AgentCard getByName(String name) {
        try {
            AgentCardDetailInfo detail = aiService.getAgentCard(name);
            AgentCard domain = AgentCardMapper.toDomain(detail);
            if (domain != null) {
                cache.put(name, domain);
                broadcast();
            }
            return domain;
        } catch (NacosException e) {
            log.warn("getAgentCard failed for {} (降级返回缓存或null): {}", name, e.getMessage());
            unreachableCallback.accept(name);
            return cache.get(name); // 降级：返回上次缓存（可能 null）
        }
    }

    /** listener 回调：更新缓存 + 广播。 */
    private void onCardEvent(String name, AgentCardDetailInfo detail) {
        AgentCard domain = AgentCardMapper.toDomain(detail);
        if (domain != null) {
            cache.put(name, domain);
        } else {
            cache.remove(name); // detail null 视为下线
        }
        broadcast();
    }

    private void broadcast() {
        watchPublisher.submit(snapshot());
    }

    /** Nacos listener 适配：把 NacosAgentCardEvent 转给 onCardEvent。 */
    private class CardListener extends AbstractNacosAgentCardListener {
        private final String name;
        CardListener(String name) { this.name = name; }

        @Override
        public void onEvent(NacosAgentCardEvent event) {
            onCardEvent(name, event.getAgentCard());
        }
    }
}

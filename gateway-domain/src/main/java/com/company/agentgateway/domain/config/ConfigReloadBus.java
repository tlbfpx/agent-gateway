package com.company.agentgateway.domain.config;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 配置重载事件总线(Sprint 1 P0):让任意来源(file modify / nacos push / k8s-configmap change / REST reload)
 * 的配置变更都能被订阅者感知,实现"改完即生效,无需重启"。
 *
 * <p>设计要点:
 * <ul>
 *   <li>domain 层抽象,实现可在 infra 层(in-memory / Redis pub-sub / Kafka)</li>
 *   <li>订阅按 configName 路由:一个 store 只关心自己 name 的事件</li>
 *   <li>事件携带 source/version/actor,用于审计与可观测</li>
 *   <li>异常隔离:订阅者抛异常不影响其他订阅者与发布者</li>
 * </ul>
 */
public interface ConfigReloadBus {

    /** 发布一次配置变更。source/version 由发布方决定(详见 {@link ConfigChanged.Source})。 */
    void publish(ConfigChanged event);

    /**
     * 订阅指定 name 的配置变更;返回的句柄可用于取消订阅。
     *
     * <p>约定:
     * <ul>
     *   <li>同一个 name 上允许有多个订阅者</li>
     *   <li>订阅者抛出异常时,总线记录日志并继续派发其他订阅者</li>
     *   <li>若 name == "*",订阅所有事件(供 UI/监控用)</li>
     * </ul>
     */
    Subscription subscribe(String name, Consumer<ConfigChanged> handler);

    /** 取消订阅。 */
    void unsubscribe(Subscription subscription);

    /** 当前已发布的最后一次事件(用于新订阅者立即拿到最新状态)。 */
    ConfigChanged lastEvent(String name);

    /** 订阅句柄(支持取消订阅)。 */
    interface Subscription {
        String name();
        void cancel();
    }
}
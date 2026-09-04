package com.company.agentgateway.domain.iam;

import java.util.concurrent.Flow;

/**
 * 出站端口：RBAC 变更事件发布（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>签名仅依赖 JDK 标准库（{@link java.util.concurrent.Flow}），不引入 Spring/Reactor。
 * <p>实现：NacosRbacChangePublisher（gateway-infra-security）— 通过 Nacos publishConfig
 * 把事件广播到所有网关实例，本地 InMemory 实现通过同实例 Flow 订阅做 write-through 缓存失效。
 */
public interface RbacChangePublisher {

    /**
     * 发布 RBAC 变更事件，返回可订阅的 Flow.Publisher。
     *
     * <p>契约：
     * <ul>
     *   <li>返回的 Publisher 必须立即可订阅（实现内部已 submit）</li>
     *   <li>订阅者按 onNext → onComplete 顺序接收；背压由订阅者控制</li>
     *   <li>失败语义：内部 catch + log warn，不回滚调用方（design §2.2）</li>
     * </ul>
     */
    Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event);
}

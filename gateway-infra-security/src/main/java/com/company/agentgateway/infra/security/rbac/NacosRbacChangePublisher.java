package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Nacos RBAC 变更发布器（spec §GW-RBAC-002 + design §2.2 · §3.2）。
 *
 * <p>一期：占位实现，发布时 log + JDK Flow submit。Data ID 命名遵循
 * {@code gateway.rbac.{tenant}.roles}（spec §19.4 字面值）。
 *
 * <p>二期：接入 gateway-infra-nacos 的 nacos-client，
 * publish 时调 nacosClient.publishConfig(dataId, json)。
 *
 * <p>失败语义：catch + log warn，不回滚调用方（design §2.2）。
 */
@Component
@ConditionalOnMissingBean(RbacChangePublisher.class)
public class NacosRbacChangePublisher implements RbacChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(NacosRbacChangePublisher.class);

    private final SubmissionPublisher<RbacChangeEvent> publisher = new SubmissionPublisher<>();

    @Override
    public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
        try {
            String dataId = dataIdFor(event.tenant());
            log.info("RBAC change [phase-1 stub] dataId={} kind={} roleId={} userId={} actor={}",
                    dataId, event.kind(), event.roleId(), event.userId(), event.actor());
            publisher.submit(event);
        } catch (Exception e) {
            log.warn("RBAC change publish failed (swallowed): {}", e.getMessage());
        }
        return publisher;
    }

    /** spec §19.4 字面值：gateway.rbac.{tenant}.roles */
    public String dataIdFor(TenantId tenant) {
        return "gateway.rbac." + tenant.value() + ".roles";
    }

    /** 供提前订阅的 Flow 视图（同实例 write-through 缓存失效用；publish 返回同一实例）。 */
    public Flow.Publisher<RbacChangeEvent> asPublisher() {
        return publisher;
    }
}

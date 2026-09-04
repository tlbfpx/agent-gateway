package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RBAC OTel Counter 注册器（spec §GW-RBAC-008 · design §5.2）。
 *
 * <p>两个 Counter：
 * <ul>
 *   <li>{@code rbac.allowed}：attributes = check_point, tenant, user, agent/model, decision="allowed"</li>
 *   <li>{@code rbac.denied}：attributes = check_point, tenant, user, agent, decision="denied", reason</li>
 * </ul>
 *
 * <p>preview 路径（CheckPoint.PREVIEW）不上 OTel（spec §GW-RBAC-010 注释）。
 */
@Component
public class RbacMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> allowedCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> deniedCache = new ConcurrentHashMap<>();

    public RbacMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAllowed(RbacDecisionEvent ev) {
        if (ev.checkPoint() == RbacDecisionEvent.CheckPoint.PREVIEW) return;
        Counter c = allowedCache.computeIfAbsent(allowedKey(ev), k ->
                Counter.builder("rbac.allowed")
                        .description("RBAC decision ALLOWED count")
                        .tag("check_point", ev.checkPoint().value())
                        .tag("tenant", ev.tenant().value())
                        .tag("user", ev.user().value())
                        .tag(agentOrModelTagName(ev), agentOrModelTagValue(ev))
                        .tag("decision", "allowed")
                        .register(registry));
        c.increment();
    }

    public void recordDenied(RbacDecisionEvent ev) {
        if (ev.checkPoint() == RbacDecisionEvent.CheckPoint.PREVIEW) return;
        Counter c = deniedCache.computeIfAbsent(deniedKey(ev), k ->
                Counter.builder("rbac.denied")
                        .description("RBAC decision DENIED count")
                        .tag("check_point", ev.checkPoint().value())
                        .tag("tenant", ev.tenant().value())
                        .tag("user", ev.user().value())
                        .tag(agentOrModelTagName(ev), agentOrModelTagValue(ev))
                        .tag("decision", "denied")
                        .tag("reason", ev.reason().value())
                        .register(registry));
        c.increment();
    }

    private static String agentOrModelTagName(RbacDecisionEvent ev) {
        return ev.agentName() != null ? "agent" : "model";
    }
    private static String agentOrModelTagValue(RbacDecisionEvent ev) {
        return ev.agentName() != null ? ev.agentName()
                : (ev.model() != null ? ev.model().value() : "unknown");
    }
    private static String allowedKey(RbacDecisionEvent ev) {
        return ev.checkPoint() + "|" + ev.tenant() + "|" + ev.user() + "|" + agentOrModelTagName(ev) + "|" + agentOrModelTagValue(ev);
    }
    private static String deniedKey(RbacDecisionEvent ev) {
        return allowedKey(ev) + "|" + ev.reason();
    }
}

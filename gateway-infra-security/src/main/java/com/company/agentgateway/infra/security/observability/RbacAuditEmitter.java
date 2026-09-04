package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * RBAC DENIED 审计写入器（spec §GW-RBAC-009 · design §2.4）。
 *
 * <p>DENIED 写 AuditRepository（事件类型 RBAC_DENIED）；ALLOWED 不写（D1-3 决策）。
 * <p>写入失败 catch + log warn，不阻断主决策（spec §GW-RBAC-009 注释）。
 */
@Component
public class RbacAuditEmitter {

    private static final Logger log = LoggerFactory.getLogger(RbacAuditEmitter.class);

    private final AuditRepository auditRepository;

    public RbacAuditEmitter(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void emit(RbacDecisionEvent event) {
        if (event.allowed()) return; // D1-3：ALLOWED 不写
        try {
            String resourceType = event.agentName() != null ? "rbac:agent" : "rbac:model";
            String resourceId = event.agentName() != null ? event.agentName()
                    : (event.model() != null ? event.model().value() : "unknown");
            String detail = "reason=" + event.reason().value() + ";check_point=" + event.checkPoint().value();
            auditRepository.append(new AuditRepository.AuditLog(
                    event.eventId(),
                    event.tenant(),
                    event.user() != null ? event.user().value() : "unknown",
                    AuditRepository.AuditLog.ActorType.HUMAN,
                    AuditRepository.AuditEventType.RBAC_DENIED,
                    Instant.now(),
                    resourceType,
                    resourceId,
                    "denied",
                    AuditRepository.AuditLog.Result.FAILURE,
                    detail));
        } catch (Exception e) {
            log.warn("RbacAuditEmitter emit failed (swallowed): {}", e.getMessage());
        }
    }
}

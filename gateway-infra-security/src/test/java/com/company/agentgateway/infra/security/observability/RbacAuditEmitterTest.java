package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RbacAuditEmitterTest {

    @Test
    void emitDenied_callsAuditRepository_withRBAC_DENIED() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-1", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NO_GRANT,
                false, Instant.now());
        emitter.emit(ev);
        ArgumentCaptor<AuditRepository.AuditLog> cap = ArgumentCaptor.forClass(AuditRepository.AuditLog.class);
        verify(repo, times(1)).append(cap.capture());
        assertThat(cap.getValue().eventType()).isEqualTo(AuditRepository.AuditEventType.RBAC_DENIED);
        assertThat(cap.getValue().resourceType()).isEqualTo("rbac:agent");
        assertThat(cap.getValue().resourceId()).isEqualTo("hr-agent");
        assertThat(cap.getValue().result()).isEqualTo(AuditRepository.AuditLog.Result.FAILURE);
        assertThat(cap.getValue().errorMessage()).contains("no_grant");
        assertThat(cap.getValue().errorMessage()).contains("rbac_filter");
    }

    @Test
    void emitAllowed_doesNotCallAuditRepository() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-2", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NONE,
                true, Instant.now());
        emitter.emit(ev);
        verify(repo, never()).append(any());
    }

    @Test
    void emitDenied_auditFailure_doesNotPropagate() {
        AuditRepository repo = mock(AuditRepository.class);
        doThrow(new RuntimeException("audit storage down")).when(repo).append(any());
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-3", new TenantId("t1"), new UserId("u1"),
                null, new ModelId("qwen"),
                RbacDecisionEvent.CheckPoint.A2A,
                RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION,
                false, Instant.now());
        // 不抛（catch + warn 吞掉）
        emitter.emit(ev);
    }

    @Test
    void emitDenied_modelResource_carriesModelIdInResourceId() {
        AuditRepository repo = mock(AuditRepository.class);
        RbacAuditEmitter emitter = new RbacAuditEmitter(repo);
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "evt-4", new TenantId("t1"), new UserId("u1"),
                null, new ModelId("qwen"),
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION,
                false, Instant.now());
        emitter.emit(ev);
        ArgumentCaptor<AuditRepository.AuditLog> cap = ArgumentCaptor.forClass(AuditRepository.AuditLog.class);
        verify(repo).append(cap.capture());
        assertThat(cap.getValue().resourceType()).isEqualTo("rbac:model");
        assertThat(cap.getValue().resourceId()).isEqualTo("qwen");
    }
}

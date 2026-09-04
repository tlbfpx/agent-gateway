package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationServiceImplTest {

    private final AuthorizationService svc = new AuthorizationServiceImpl();

    private AuthPrincipal principal(boolean withAgent, boolean withModel) {
        return new AuthPrincipal(
                new UserId("u1"), new TenantId("t1"),
                withAgent ? Set.of(new AgentGrant("hr-agent", Set.of())) : Set.of(),
                withModel ? Set.of(new ModelId("qwen")) : Set.of(),
                AuthChannel.API_KEY);
    }

    @Test
    void canInvokeAgent_有授权返回true_无授权false() {
        assertThat(svc.canInvokeAgent(principal(true, false), "hr-agent")).isTrue();
        assertThat(svc.canInvokeAgent(principal(false, false), "hr-agent")).isFalse();
    }

    @Test
    void canUseModel_有授权true_无授权false() {
        assertThat(svc.canUseModel(principal(false, true), new ModelId("qwen"))).isTrue();
        assertThat(svc.canUseModel(principal(false, false), new ModelId("qwen"))).isFalse();
    }

    @Test
    void checkInvokeAgent_有授权不抛_无授权抛() {
        svc.checkInvokeAgent(principal(true, false), "hr-agent"); // 不抛
        assertThatThrownBy(() -> svc.checkInvokeAgent(principal(false, false), "hr-agent"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("not authorized to invoke agent");
    }

    @Test
    void checkUseModel_有授权不抛_无授权抛() {
        svc.checkUseModel(principal(false, true), new ModelId("qwen")); // 不抛
        assertThatThrownBy(() -> svc.checkUseModel(principal(false, false), new ModelId("qwen")))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("not authorized to use model");
    }

    @Test
    void null入参安全返回false_不抛NPE() {
        assertThat(svc.canInvokeAgent(null, "x")).isFalse();
        assertThat(svc.canInvokeAgent(principal(false, false), null)).isFalse();
        assertThat(svc.canUseModel(null, new ModelId("x"))).isFalse();
        assertThat(svc.canUseModel(principal(false, false), null)).isFalse();
    }

    @Test
    void null入参checkThrow抛AuthorizationException() {
        assertThatThrownBy(() -> svc.checkInvokeAgent(null, "x"))
                .isInstanceOf(AuthorizationException.class);
    }

    // ====== D1 新增：决策并集测试（既有 6 条不变） ======

    private final com.company.agentgateway.domain.iam.RoleRepository roleRepo =
            new com.company.agentgateway.infra.security.rbac.InMemoryRoleRepository();
    private final com.company.agentgateway.domain.iam.RoleBindingRepository bindingRepo =
            new com.company.agentgateway.infra.security.rbac.InMemoryRoleBindingRepository();

    private AuthPrincipal principalOnlyRole() {
        // principal 字段空，但通过 Role 聚合获得权限
        return new AuthPrincipal(
                new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
    }

    @Test
    void d1_onlyRolePermission_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("hr-agent", Set.of())));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        assertThat(svcUpgraded.canInvokeAgent(principalOnlyRole(), "hr-agent")).isTrue();
    }

    @Test
    void d1_onlyPrincipalFieldPermission_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("other-agent", Set.of())));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), t,
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isTrue();
    }

    @Test
    void d1_unionOfPrincipalAndRole_canInvokeAgent() {
        var t = new TenantId("t1");
        var r = new com.company.agentgateway.domain.iam.Role(
                new com.company.agentgateway.domain.shared.RoleId("r1"), "r", "d",
                Set.of(new com.company.agentgateway.domain.iam.AgentPermission("finance-agent", Set.of())));
        roleRepo.save(t, r);
        bindingRepo.bind(t, new UserId("u1"), new com.company.agentgateway.domain.shared.RoleId("r1"));

        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), t,
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        // 任一命中即 true
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isTrue();
        assertThat(svcUpgraded.canInvokeAgent(p, "finance-agent")).isTrue();
        assertThat(svcUpgraded.canInvokeAgent(p, "unknown")).isFalse();
    }

    @Test
    void d1_noGrant_noBinding_returnsFalse() {
        AuthorizationService svcUpgraded = new AuthorizationServiceImpl(roleRepo, bindingRepo);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        assertThat(svcUpgraded.canInvokeAgent(p, "hr-agent")).isFalse();
        assertThat(svcUpgraded.canUseModel(p, new ModelId("qwen"))).isFalse();
    }

    // ====== D1 C 阶段新增：可观测与审计（spec §GW-RBAC-008/009） ======

    @Test
    void c1_deniedPath_writesAuditAndCounter_withCheckPointRbacFilter() {
        com.company.agentgateway.domain.audit.AuditRepository auditRepo =
                org.mockito.Mockito.mock(com.company.agentgateway.domain.audit.AuditRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterReg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var emitter = new com.company.agentgateway.infra.security.observability.RbacAuditEmitter(auditRepo);
        var metrics = new com.company.agentgateway.infra.security.observability.RbacMetrics(meterReg);

        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        var svcFull = new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics);
        assertThatThrownBy(() -> svcFull.checkInvokeAgent(p, "hr-agent",
                com.company.agentgateway.domain.iam.RbacCheckPoint.RBAC_FILTER))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("GW-1003");
        // DENIED：写审计 1 次 + Counter 1 次（reason=no_role_binding：无任何绑定）
        org.mockito.Mockito.verify(auditRepo, org.mockito.Mockito.times(1))
                .append(org.mockito.ArgumentMatchers.any(com.company.agentgateway.domain.audit.AuditRepository.AuditLog.class));
        assertThat(meterReg.counter("rbac.denied",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "denied",
                "reason", "no_role_binding").count()).isEqualTo(1.0);
    }

    @Test
    void c2_allowedPath_doesNotWriteAudit_butIncrementsAllowedCounter() {
        com.company.agentgateway.domain.audit.AuditRepository auditRepo =
                org.mockito.Mockito.mock(com.company.agentgateway.domain.audit.AuditRepository.class);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterReg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var emitter = new com.company.agentgateway.infra.security.observability.RbacAuditEmitter(auditRepo);
        var metrics = new com.company.agentgateway.infra.security.observability.RbacMetrics(meterReg);

        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(), AuthChannel.API_KEY);
        var svcFull = new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics);
        svcFull.checkInvokeAgent(p, "hr-agent", com.company.agentgateway.domain.iam.RbacCheckPoint.A2A); // 不抛
        // ALLOWED：不写审计 + Counter 1 次（check_point=a2a）
        org.mockito.Mockito.verify(auditRepo, org.mockito.Mockito.never())
                .append(org.mockito.ArgumentMatchers.any(com.company.agentgateway.domain.audit.AuditRepository.AuditLog.class));
        assertThat(meterReg.counter("rbac.allowed",
                "check_point", "a2a",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "allowed").count()).isEqualTo(1.0);
    }
}

package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.infra.security.observability.RbacAuditEmitter;
import com.company.agentgateway.infra.security.observability.RbacMetrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AuthorizationService 实现（spec §6.3 + §GW-RBAC-005/008/009/010）。
 *
 * <p>构造器形态（向后兼容）：
 * <ul>
 *   <li>{@code new AuthorizationServiceImpl()} — 降级模式：仅读 principal 字段，无埋点。
 *       保留既有 6 条测试零修改基线（决策一致性证据）。</li>
 *   <li>{@code new AuthorizationServiceImpl(roleRepo, bindingRepo)} — 升级模式：决策并集，无埋点。</li>
 *   <li>{@code new AuthorizationServiceImpl(roleRepo, bindingRepo, emitter, metrics)} — 全功能：
 *       决策并集 + check_point 维度 Counter + DENIED 审计（C.3）。</li>
 * </ul>
 *
 * <p>决策顺序（spec §GW-RBAC-005）：扁平字段优先 → Role/Binding 聚合并集。
 * <p>埋点（spec §GW-RBAC-008/009/010）：check* 路径；ALLOWED 只打 Counter，DENIED 打 Counter + 写审计。
 */
public class AuthorizationServiceImpl implements AuthorizationService {

    private final RoleRepository roleRepository;               // nullable：降级模式
    private final RoleBindingRepository roleBindingRepository; // nullable：降级模式
    private final RbacAuditEmitter auditEmitter;              // nullable：无则不写审计
    private final RbacMetrics metrics;                        // nullable：无则不打 Counter

    public AuthorizationServiceImpl() {
        this(null, null, null, null);
    }

    public AuthorizationServiceImpl(RoleRepository roleRepository, RoleBindingRepository roleBindingRepository) {
        this(roleRepository, roleBindingRepository, null, null);
    }

    public AuthorizationServiceImpl(RoleRepository roleRepository, RoleBindingRepository roleBindingRepository,
                                     RbacAuditEmitter auditEmitter, RbacMetrics metrics) {
        this.roleRepository = roleRepository;
        this.roleBindingRepository = roleBindingRepository;
        this.auditEmitter = auditEmitter;
        this.metrics = metrics;
    }

    // ===== 既有 4 方法（签名零变化；委托到带 checkPoint 的实现） =====

    @Override
    public boolean canInvokeAgent(AuthPrincipal principal, String agentName) {
        if (principal == null || agentName == null) return false;
        if (principal.canInvoke(agentName)) return true;
        return aggregateAgentPermission(principal, agentName);
    }

    @Override
    public boolean canUseModel(AuthPrincipal principal, ModelId model) {
        if (principal == null || model == null) return false;
        if (principal.canUse(model)) return true;
        return aggregateModelPermission(principal, model);
    }

    @Override
    public void checkInvokeAgent(AuthPrincipal principal, String agentName) {
        checkInvokeAgent(principal, agentName, RbacCheckPoint.RBAC_FILTER);
    }

    @Override
    public void checkUseModel(AuthPrincipal principal, ModelId model) {
        checkUseModel(principal, model, RbacCheckPoint.RBAC_FILTER);
    }

    // ===== D1 新增重载（spec §GW-RBAC-008/009/010） =====

    @Override
    public boolean canInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint) {
        return canInvokeAgent(principal, agentName); // can* 不埋点（spec §GW-RBAC-008：仅决策路径）
    }

    @Override
    public boolean canUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint) {
        return canUseModel(principal, model);
    }

    @Override
    public void checkInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint) {
        boolean allowed = canInvokeAgent(principal, agentName);
        if (allowed) {
            record(principal, agentName, null, checkPoint, true, RbacDecisionEvent.DecisionReason.NONE);
            return;
        }
        var reason = principal != null && hasBinding(principal)
                ? RbacDecisionEvent.DecisionReason.NO_GRANT
                : RbacDecisionEvent.DecisionReason.NO_ROLE_BINDING;
        record(principal, agentName, null, checkPoint, false, reason);
        throw new AuthorizationException(
                RbacErrorCode.UNAUTHORIZED +
                ": Principal " + (principal == null ? "null" : principal.user().value())
                        + " is not authorized to invoke agent: " + agentName);
    }

    @Override
    public void checkUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint) {
        boolean allowed = canUseModel(principal, model);
        if (allowed) {
            record(principal, null, model, checkPoint, true, RbacDecisionEvent.DecisionReason.NONE);
            return;
        }
        var reason = principal != null && hasBinding(principal)
                ? RbacDecisionEvent.DecisionReason.NO_GRANT
                : RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION;
        record(principal, null, model, checkPoint, false, reason);
        throw new AuthorizationException(
                RbacErrorCode.UNAUTHORIZED +
                ": Principal " + (principal == null ? "null" : principal.user().value())
                        + " is not authorized to use model: " + (model == null ? "null" : model.value()));
    }

    // ================== private helpers ==================

    /** 埋点分发：ALLOWED 只打 Counter；DENIED 打 Counter + 写审计（emitter/metrics 可空时跳过）。 */
    private void record(AuthPrincipal p, String agentName, ModelId model,
                        RbacCheckPoint cp, boolean allowed, RbacDecisionEvent.DecisionReason reason) {
        if (cp == null || p == null) return;
        RbacDecisionEvent ev = new RbacDecisionEvent(
                "rb-" + UUID.randomUUID(),
                p.tenant(), p.user(),
                agentName, model,
                toEventCheckPoint(cp), reason, allowed, Instant.now());
        if (metrics != null) {
            if (allowed) metrics.recordAllowed(ev);
            else metrics.recordDenied(ev);
        }
        if (!allowed && auditEmitter != null) {
            auditEmitter.emit(ev);
        }
    }

    private static RbacDecisionEvent.CheckPoint toEventCheckPoint(RbacCheckPoint cp) {
        return switch (cp) {
            case RBAC_FILTER -> RbacDecisionEvent.CheckPoint.RBAC_FILTER;
            case A2A -> RbacDecisionEvent.CheckPoint.A2A;
            case PREVIEW -> RbacDecisionEvent.CheckPoint.PREVIEW;
        };
    }

    private boolean hasBinding(AuthPrincipal principal) {
        if (roleBindingRepository == null) return true; // 降级模式：归因 no_grant
        return !roleBindingRepository.findByUser(principal.tenant(), principal.user()).isEmpty();
    }

    private boolean aggregateAgentPermission(AuthPrincipal principal, String agentName) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        if (bindings.isEmpty()) return false;
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof AgentPermission ap && ap.agentName().equals(agentName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean aggregateModelPermission(AuthPrincipal principal, ModelId model) {
        if (roleRepository == null || roleBindingRepository == null) return false;
        List<RoleId> bindings = roleBindingRepository.findByUser(principal.tenant(), principal.user());
        for (RoleId roleId : bindings) {
            var roleOpt = roleRepository.findById(principal.tenant(), roleId);
            if (roleOpt.isEmpty()) continue;
            for (Permission p : roleOpt.get().permissions()) {
                if (p instanceof ModelPermission mp && mp.models().contains(model)) {
                    return true;
                }
            }
        }
        return false;
    }
}

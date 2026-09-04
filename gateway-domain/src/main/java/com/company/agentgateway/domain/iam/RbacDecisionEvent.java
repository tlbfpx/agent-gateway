package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.time.Instant;

/**
 * 决策事件（design §4.2）。OTel Counter attribute + AuditRepository 共享载体。
 *
 * <p>checkPoint 维度贯穿评估链入口（spec §GW-RBAC-010）：
 * <ul>
 *   <li>{@code rbac_filter}：RbacFilter 入口（gateway-interfaces）</li>
 *   <li>{@code a2a}：A2A 调用前二次校验（gateway-infra-a2a）</li>
 *   <li>{@code preview}：纯函数 preview，不上 OTel（仅单测内部追踪）</li>
 * </ul>
 *
 * <p>decisionReason 维度（spec §GW-RBAC-008）：
 * <ul>
 *   <li>{@code no_grant}：principal.agentGrants / allowedModels 命中失败</li>
 *   <li>{@code no_role_binding}：RoleBindingRepository 未绑定</li>
 *   <li>{@code no_model_permission}：ModelPermission 聚合无命中</li>
 * </ul>
 */
public record RbacDecisionEvent(String eventId, TenantId tenant, UserId user,
                                 String agentName, ModelId model,
                                 CheckPoint checkPoint, DecisionReason reason,
                                 boolean allowed, Instant timestamp) {

    public enum CheckPoint {
        RBAC_FILTER("rbac_filter"), A2A("a2a"), PREVIEW("preview");
        private final String value;
        CheckPoint(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum DecisionReason {
        NO_GRANT("no_grant"),
        NO_ROLE_BINDING("no_role_binding"),
        NO_MODEL_PERMISSION("no_model_permission"),
        NONE("");
        private final String value;
        DecisionReason(String value) { this.value = value; }
        public String value() { return value; }
    }
}

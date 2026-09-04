package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.time.Instant;

/**
 * 角色/绑定变更事件（spec §GW-RBAC-002 + design §4.2）。
 *
 * <p>Nacos Data ID：gateway.rbac.{tenant}.roles（spec §19.4 字面值）
 */
public record RbacChangeEvent(Kind kind, TenantId tenant, RoleId roleId,
                              UserId userId, String actor, Instant timestamp) {

    public enum Kind {
        ROLE_UPSERT, ROLE_DELETE, BIND, UNBIND
    }
}

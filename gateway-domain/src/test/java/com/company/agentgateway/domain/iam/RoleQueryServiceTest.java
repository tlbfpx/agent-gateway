package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class RoleQueryServiceTest {

    private final RoleQueryService svc = new RoleQueryService();

    private Role role(RoleId id, Set<Permission> perms) {
        return new Role(id, "name-" + id.value(), "desc", perms);
    }

    @Test
    void preview_pureFunction_returnsAggregatedAgentsAndModels() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(
                role(new RoleId("r1"), Set.of(new AgentPermission("hr-agent", Set.of()))),
                role(new RoleId("r2"), Set.of(
                        new AgentPermission("finance-agent", Set.of()),
                        new ModelPermission(Set.of(new ModelId("qwen"))))),
                role(new RoleId("r3"), Set.of(new AgentPermission("hr-agent", Set.of("salary"))
                )));
        // u 绑定 r1 + r2 + r3
        List<RoleId> bindings = List.of(new RoleId("r1"), new RoleId("r2"), new RoleId("r3"));

        PolicyPreview pp = svc.preview(snapshot, bindings, u, t);

        assertThat(pp.allowedAgents()).containsExactlyInAnyOrder("hr-agent", "finance-agent");
        assertThat(pp.allowedModels()).containsExactly(new ModelId("qwen"));
    }

    @Test
    void preview_isIdempotent_sameInputSameOutput() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(
                role(new RoleId("r1"), Set.of(new AgentPermission("a", Set.of()))));
        List<RoleId> bindings = List.of(new RoleId("r1"));

        PolicyPreview p1 = svc.preview(snapshot, bindings, u, t);
        PolicyPreview p2 = svc.preview(snapshot, bindings, u, t);
        PolicyPreview p3 = svc.preview(snapshot, bindings, u, t);

        // 幂等：连发 10 次都 equals（spec §GW-RBAC-011）
        for (int i = 0; i < 10; i++) {
            assertThat(svc.preview(snapshot, bindings, u, t)).isEqualTo(p1);
        }
        assertThat(p2).isEqualTo(p1).isEqualTo(p3);
    }

    @Test
    void preview_emptyBindings_returnsEmptyPreview() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        PolicyPreview pp = svc.preview(List.of(), List.of(), u, t);
        assertThat(pp.allowedAgents()).isEmpty();
        assertThat(pp.allowedModels()).isEmpty();
    }

    @Test
    void preview_skillPermissions_areIgnored_phase1() {
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        List<Role> snapshot = List.of(role(new RoleId("r1"),
                Set.of(new SkillPermission("a", "s"))));
        List<RoleId> bindings = List.of(new RoleId("r1"));
        PolicyPreview pp = svc.preview(snapshot, bindings, u, t);
        // D1-4：SkillPermission 一期数据空，preview 聚合时跳过
        assertThat(pp.allowedAgents()).isEmpty();
        assertThat(pp.allowedModels()).isEmpty();
    }
}

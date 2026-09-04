package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** 补全 domain 层覆盖率：PolicyEvaluator.evaluatePermission 总入口 + RbacDecisionEvent 枚举 value()。 */
class PolicyEvaluatorCoverageTest {

    @Test
    void evaluatePermission_dispatchesAllThreeBranches() {
        // Agent 分支
        assertThat(PolicyEvaluator.evaluatePermission(
                new AgentPermission("a1", Set.of()), "a1", null)).contains(true);
        // Model 分支
        assertThat(PolicyEvaluator.evaluatePermission(
                new ModelPermission(Set.of(new ModelId("m1"))), null, new ModelId("m1"))).contains(true);
        // Skill 分支（D1-4 一期数据空 → empty）
        assertThat(PolicyEvaluator.evaluatePermission(
                new SkillPermission("a1", "s1"), null, null)).isEmpty();
    }

    @Test
    void rbacDecisionEvent_enumValueStrings_areStable() {
        // OTel/审计 attribute 字符串稳定性（spec §GW-RBAC-008/010）
        assertThat(RbacDecisionEvent.CheckPoint.RBAC_FILTER.value()).isEqualTo("rbac_filter");
        assertThat(RbacDecisionEvent.CheckPoint.A2A.value()).isEqualTo("a2a");
        assertThat(RbacDecisionEvent.CheckPoint.PREVIEW.value()).isEqualTo("preview");
        assertThat(RbacDecisionEvent.DecisionReason.NO_GRANT.value()).isEqualTo("no_grant");
        assertThat(RbacDecisionEvent.DecisionReason.NO_ROLE_BINDING.value()).isEqualTo("no_role_binding");
        assertThat(RbacDecisionEvent.DecisionReason.NO_MODEL_PERMISSION.value()).isEqualTo("no_model_permission");
        assertThat(RbacDecisionEvent.DecisionReason.NONE.value()).isEmpty();
    }

    @Test
    void rbacDecisionEvent_carriesAllFields() {
        Instant now = Instant.now();
        RbacDecisionEvent ev = new RbacDecisionEvent("e1",
                new com.company.agentgateway.domain.shared.TenantId("t1"),
                new com.company.agentgateway.domain.shared.UserId("u1"),
                "a1", new ModelId("m1"),
                RbacDecisionEvent.CheckPoint.A2A,
                RbacDecisionEvent.DecisionReason.NO_GRANT,
                false, now);
        assertThat(ev.eventId()).isEqualTo("e1");
        assertThat(ev.agentName()).isEqualTo("a1");
        assertThat(ev.model()).isEqualTo(new ModelId("m1"));
        assertThat(ev.allowed()).isFalse();
        assertThat(ev.timestamp()).isEqualTo(now);
    }
}

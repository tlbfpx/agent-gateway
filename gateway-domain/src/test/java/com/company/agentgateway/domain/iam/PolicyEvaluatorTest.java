package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyEvaluatorTest {

    @Test
    void evaluateAgentPermission_returnsTrue_whenAgentNameMatches() {
        AgentPermission ap = new AgentPermission("hr-agent", Set.of());
        Optional<Boolean> result = PolicyEvaluator.evaluateAgent(ap, "hr-agent");
        assertThat(result).contains(true);
    }

    @Test
    void evaluateAgentPermission_returnsFalse_whenAgentNameDiffers() {
        AgentPermission ap = new AgentPermission("hr-agent", Set.of());
        Optional<Boolean> result = PolicyEvaluator.evaluateAgent(ap, "other-agent");
        assertThat(result).contains(false);
    }

    @Test
    void evaluateModelPermission_returnsTrue_whenModelInSet() {
        ModelPermission mp = new ModelPermission(Set.of(new ModelId("qwen"), new ModelId("gpt4")));
        Optional<Boolean> result = PolicyEvaluator.evaluateModel(mp, new ModelId("qwen"));
        assertThat(result).contains(true);
    }

    @Test
    void evaluateSkillPermission_returnsEmpty_deferredToPhase2() {
        SkillPermission sp = new SkillPermission("a1", "s1");
        // 一期 D1-4 决策：SkillPermission 数据空，返回 empty 让调用方 skip
        assertThat(PolicyEvaluator.evaluateSkill(sp)).isEmpty();
    }
}

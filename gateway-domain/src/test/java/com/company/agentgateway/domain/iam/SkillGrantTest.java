package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SkillGrantTest {

    @Test
    void 空allowedSkills全授权() {
        var g = new AgentGrant("hr", Set.of());
        assertThat(g.allowsSkill("请假")).isTrue();
        assertThat(g.filterSkills(List.of("请假", "报销"))).containsExactlyInAnyOrder("请假", "报销");
    }

    @Test
    void 非空只授权列出的() {
        var g = new AgentGrant("hr", Set.of("请假"));
        assertThat(g.allowsSkill("请假")).isTrue();
        assertThat(g.allowsSkill("报销")).isFalse();
        assertThat(g.filterSkills(List.of("请假", "报销"))).containsExactly("请假");
    }

    @Test
    void 全滤空返回空集() {
        var g = new AgentGrant("hr", Set.of("不存在"));
        assertThat(g.filterSkills(List.of("请假"))).isEmpty();
    }
}

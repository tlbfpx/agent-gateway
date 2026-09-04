package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SkillPermissionTest {

    @Test
    void blankAgentName_throws() {
        assertThatThrownBy(() -> new SkillPermission("", "s1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentName");
        assertThatThrownBy(() -> new SkillPermission(null, "s1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSkillName_throws() {
        assertThatThrownBy(() -> new SkillPermission("a1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skillName");
        assertThatThrownBy(() -> new SkillPermission("a1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validConstruction() {
        SkillPermission sp = new SkillPermission("hr-agent", "ask-leave");
        assertThat(sp.agentName()).isEqualTo("hr-agent");
        assertThat(sp.skillName()).isEqualTo("ask-leave");
    }

    @Test
    void equalsAndHashCode() {
        SkillPermission a = new SkillPermission("a1", "s1");
        SkillPermission b = new SkillPermission("a1", "s1");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}

package com.company.agentgateway.domain.iam;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentPermissionTest {

    @Test
    void blankAgentName_throws() {
        assertThatThrownBy(() -> new AgentPermission("", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentName");
        assertThatThrownBy(() -> new AgentPermission(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowedSkills_isImmutable() {
        Set<String> mutable = new java.util.HashSet<>();
        mutable.add("s1");
        AgentPermission ap = new AgentPermission("a1", mutable);
        mutable.add("s2"); // mutate after construction
        assertThat(ap.allowedSkills()).hasSize(1).containsExactly("s1");
    }

    @Test
    void equalsAndHashCode() {
        AgentPermission a = new AgentPermission("a1", Set.of("s1"));
        AgentPermission b = new AgentPermission("a1", Set.of("s1"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void emptyAllowedSkills_meansFullGrant() {
        AgentPermission ap = new AgentPermission("a1", Set.of());
        assertThat(ap.allowedSkills()).isEmpty();
    }
}

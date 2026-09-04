package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PermissionSealedTest {

    @Test
    void patternMatching_AgentPermission_extracted() {
        Permission p = new AgentPermission("hr-agent", Set.of());
        String agentName = switch (p) {
            case AgentPermission ap -> ap.agentName();
            default -> throw new IllegalStateException("not AgentPermission");
        };
        assertThat(agentName).isEqualTo("hr-agent");
    }

    @Test
    void patternMatching_ModelPermission_extracted() {
        Permission p = new ModelPermission(Set.of(new ModelId("qwen")));
        Set<ModelId> models = switch (p) {
            case ModelPermission mp -> mp.models();
            default -> throw new IllegalStateException("not ModelPermission");
        };
        assertThat(models).hasSize(1);
    }

    @Test
    void patternMatching_SkillPermission_extracted() {
        Permission p = new SkillPermission("hr-agent", "ask-leave");
        String skillName = switch (p) {
            case SkillPermission sp -> sp.skillName();
            default -> throw new IllegalStateException("not SkillPermission");
        };
        assertThat(skillName).isEqualTo("ask-leave");
    }
}
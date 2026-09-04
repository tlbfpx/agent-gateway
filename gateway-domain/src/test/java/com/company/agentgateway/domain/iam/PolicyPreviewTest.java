package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyPreviewTest {

    @Test
    void preview_carriesUserTenantAndSets() {
        PolicyPreview p = new PolicyPreview(
                new UserId("u1"), new TenantId("t1"),
                Set.of("hr-agent", "finance-agent"),
                Set.of(new ModelId("qwen")));
        assertThat(p.user().value()).isEqualTo("u1");
        assertThat(p.tenant().value()).isEqualTo("t1");
        assertThat(p.allowedAgents()).containsExactlyInAnyOrder("hr-agent", "finance-agent");
        assertThat(p.allowedModels()).containsExactly(new ModelId("qwen"));
    }

    @Test
    void preview_isImmutable() {
        var mutableAgents = new java.util.HashSet<String>();
        mutableAgents.add("a1");
        var mutableModels = new java.util.HashSet<ModelId>();
        mutableModels.add(new ModelId("m1"));
        PolicyPreview p = new PolicyPreview(new UserId("u1"), new TenantId("t1"),
                mutableAgents, mutableModels);
        mutableAgents.add("a2");
        mutableModels.add(new ModelId("m2"));
        assertThat(p.allowedAgents()).hasSize(1);
        assertThat(p.allowedModels()).hasSize(1);
    }
}

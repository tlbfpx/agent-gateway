package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RbacCheckPointTest {

    @Test
    void enum_has_three_values() {
        // spec §GW-RBAC-010 要求三个 check_point 分流
        assertThat(RbacCheckPoint.values()).hasSize(3);
        assertThat(RbacCheckPoint.RBAC_FILTER.name()).isEqualTo("RBAC_FILTER");
        assertThat(RbacCheckPoint.A2A.name()).isEqualTo("A2A");
        assertThat(RbacCheckPoint.PREVIEW.name()).isEqualTo("PREVIEW");
    }

    @Test
    void values_are_stable_serialization() {
        // spec §归档闸门要求字符串稳定（审计/OTel attribute）
        assertThat(RbacCheckPoint.RBAC_FILTER.name()).isEqualTo("RBAC_FILTER");
        assertThat(RbacCheckPoint.A2A.name()).isEqualTo("A2A");
        assertThat(RbacCheckPoint.PREVIEW.name()).isEqualTo("PREVIEW");
    }
}

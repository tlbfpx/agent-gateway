package com.company.agentgateway.domain.iam;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RbacErrorCodeTest {

    @Test
    void constants_alignWithSpec() {
        // spec §13.4 + proposal.md §错误码段分配
        assertThat(RbacErrorCode.UNAUTHORIZED).isEqualTo("GW-1003");
        assertThat(RbacErrorCode.ROLE_NOT_FOUND).isEqualTo("GW-1010");
        assertThat(RbacErrorCode.ROLE_BINDING_CONFLICT).isEqualTo("GW-1011");
        assertThat(RbacErrorCode.ROLE_PERMISSION_INVALID).isEqualTo("GW-1012");
        assertThat(RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND).isEqualTo("GW-1013");
        assertThat(RbacErrorCode.ADMIN_RBAC_FALLBACK).isEqualTo("GW-4204");
    }

    @Test
    void allErrorCodes_haveUniqueValue() {
        String[] all = {
            RbacErrorCode.UNAUTHORIZED,
            RbacErrorCode.ROLE_NOT_FOUND,
            RbacErrorCode.ROLE_BINDING_CONFLICT,
            RbacErrorCode.ROLE_PERMISSION_INVALID,
            RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND,
            RbacErrorCode.ADMIN_RBAC_FALLBACK
        };
        assertThat(java.util.Set.of(all)).hasSize(all.length);
    }
}

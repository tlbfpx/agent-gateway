package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RoleTest {

    @Test
    void blankName_throws() {
        assertThatThrownBy(() -> new Role(new RoleId("r1"), "", "desc", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void nameExceeds64Chars_throws() {
        String longName = "a".repeat(65);
        assertThatThrownBy(() -> new Role(new RoleId("r1"), longName, "desc", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void descriptionExceeds256Chars_throws() {
        String longDesc = "a".repeat(257);
        assertThatThrownBy(() -> new Role(new RoleId("r1"), "r", longDesc, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void permissionsExceeds100_throws() {
        Set<Permission> perms = new HashSet<>();
        for (int i = 0; i < 101; i++) {
            perms.add(new AgentPermission("a" + i, Set.of()));
        }
        assertThatThrownBy(() -> new Role(new RoleId("r1"), "r", "d", perms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions");
    }

    @Test
    void permissions_isImmutable() {
        Set<Permission> mutable = new HashSet<>();
        mutable.add(new AgentPermission("a1", Set.of()));
        Role role = new Role(new RoleId("r1"), "r", "d", mutable);
        mutable.add(new AgentPermission("a2", Set.of()));
        assertThat(role.permissions()).hasSize(1);
    }

    @Test
    void validConstruction() {
        Role r = new Role(new RoleId("r1"), "admin", "管理员角色",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        assertThat(r.id()).isEqualTo(new RoleId("r1"));
        assertThat(r.name()).isEqualTo("admin");
        assertThat(r.description()).isEqualTo("管理员角色");
        assertThat(r.permissions()).hasSize(1);
    }
}
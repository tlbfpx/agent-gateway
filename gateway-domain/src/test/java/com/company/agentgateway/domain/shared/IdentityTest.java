package com.company.agentgateway.domain.shared;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

class IdentityTest {
    record IdentityCase(String name, Class<? extends Record> type, String validValue, String invalidBlank, String invalidNull) {}

    static Stream<IdentityCase> identityCases() {
        return Stream.of(
            new IdentityCase("UserId", UserId.class, "user-123", " ", null),
            new IdentityCase("TenantId", TenantId.class, "tenant-abc", "  ", null),
            new IdentityCase("SessionId", SessionId.class, "session-xyz", "\t", null),
            new IdentityCase("ModelId", ModelId.class, "model-qwen", "", null),
            new IdentityCase("ApiKeyId", ApiKeyId.class, "key-456", "\n", null),
            new IdentityCase("RoleId", RoleId.class, "role-admin", " \r ", null),
            new IdentityCase("AgentVersion", AgentVersion.class, "v1.2.3", "", null)
        );
    }

    @ParameterizedTest(name = "{0} preserves value")
    @MethodSource("identityCases")
    void preservesValue(IdentityCase c) throws Exception {
        var constructor = c.type().getDeclaredConstructor(String.class);
        var instance = constructor.newInstance(c.validValue());
        var valueMethod = c.type().getDeclaredMethod("value");
        assertThat(valueMethod.invoke(instance)).isEqualTo(c.validValue());
    }

    @ParameterizedTest(name = "{0} rejects blank")
    @MethodSource("identityCases")
    void rejectsBlank(IdentityCase c) throws Exception {
        var constructor = c.type().getDeclaredConstructor(String.class);
        var thrown = catchThrowable(() -> constructor.newInstance(c.invalidBlank()));
        assertThat(thrown)
            .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
            .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} rejects null")
    @MethodSource("identityCases")
    void rejectsNull(IdentityCase c) throws Exception {
        var constructor = c.type().getDeclaredConstructor(String.class);
        var thrown = catchThrowable(() -> constructor.newInstance((String) null));
        assertThat(thrown)
            .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
            .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} equality based on value")
    @MethodSource("identityCases")
    void equalityBasedOnValue(IdentityCase c) throws Exception {
        var constructor = c.type().getDeclaredConstructor(String.class);
        var id1 = constructor.newInstance(c.validValue());
        var id2 = constructor.newInstance(c.validValue());
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).hasSameHashCodeAs(id2);
    }
}

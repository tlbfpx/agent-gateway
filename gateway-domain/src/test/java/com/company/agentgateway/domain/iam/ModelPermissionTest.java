package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelPermissionTest {

    @Test
    void emptyModels_throws() {
        assertThatThrownBy(() -> new ModelPermission(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void nullInModels_throws() {
        Set<ModelId> set = new java.util.HashSet<>();
        set.add(null);
        assertThatThrownBy(() -> new ModelPermission(set))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void models_isImmutable() {
        Set<ModelId> mutable = new java.util.HashSet<>();
        mutable.add(new ModelId("m1"));
        ModelPermission mp = new ModelPermission(mutable);
        mutable.add(new ModelId("m2"));
        assertThat(mp.models()).hasSize(1).extracting(ModelId::value).containsExactly("m1");
    }

    @Test
    void equalsAndHashCode() {
        ModelPermission a = new ModelPermission(Set.of(new ModelId("m1")));
        ModelPermission b = new ModelPermission(Set.of(new ModelId("m1")));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void containsModel_returnsTrueWhenPresent() {
        ModelPermission mp = new ModelPermission(Set.of(new ModelId("m1"), new ModelId("m2")));
        assertThat(mp.models()).contains(new ModelId("m1"));
    }
}
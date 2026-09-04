package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedModelSelectorTest {

    private static ModelDef model(String id, String displayName, int weight, boolean enabled) {
        return new ModelDef(new ModelId(id), "p", displayName, "https://e", "k",
                Set.of(), 8192, BigDecimal.ZERO, BigDecimal.ZERO,
                enabled, List.of("all"), null, weight);
    }

    private static WeightedModelSelector selector(ModelDef... models) {
        Map<String, ModelDef> byId = Stream.of(models)
                .collect(Collectors.toMap(m -> m.id().value(), m -> m));
        return new WeightedModelSelector(id -> Optional.ofNullable(byId.get(id.value())));
    }

    @Test
    void 无灰度组精确匹配() {
        var a = model("glm", "GLM", 100, true);
        var s = selector(a);
        assertThat(s.select(new ModelId("glm"), List.of(a))).isEqualTo(a);
    }

    @Test
    void 灰度组按权重分流且不出组() {
        var main = model("glm", "GLM", 100, true);
        var canary = model("glm-canary", "GLM", 0, true); // 权重 0 永不选中
        var s = selector(main, canary);
        for (int i = 0; i < 50; i++) {
            var picked = s.select(new ModelId("glm"), List.of(main, canary));
            assertThat(picked.id().value()).isEqualTo("glm");
        }
    }

    @Test
    void 权重100必中组内() {
        var main = model("glm", "GLM", 0, true);
        var canary = model("glm-canary", "GLM", 100, true);
        var s = selector(main, canary);
        assertThat(s.select(new ModelId("glm"), List.of(main, canary)).id().value())
                .isEqualTo("glm-canary");
    }

    @Test
    void 不存在返回null() {
        var s = selector(model("glm", "GLM", 100, true));
        assertThat(s.select(new ModelId("nope"), List.of())).isNull();
    }

    @Test
    void 请求禁用模型返回null() {
        var off = model("glm", "GLM", 100, false);
        var s = selector(off);
        assertThat(s.select(new ModelId("glm"), List.of(off))).isNull();
    }

    @Test
    void 大样本分流比例近似权重() {
        var main = model("glm", "GLM", 70, true);
        var canary = model("glm-canary", "GLM", 30, true);
        var s = selector(main, canary);
        int canaryHits = 0;
        for (int i = 0; i < 10_000; i++) {
            if (s.select(new ModelId("glm"), List.of(main, canary)).id().value().equals("glm-canary")) {
                canaryHits++;
            }
        }
        // 期望 3000，容差 ±5%
        assertThat(canaryHits).isBetween(2500, 3500);
    }
}

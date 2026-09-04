package com.company.agentgateway.domain.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryNormalizerTest {

    @Test
    @DisplayName("英文:去标点 + 小写 + 去停用词")
    void englishNormalize() {
        String n = QueryNormalizer.normalize("What's the weather like in Tokyo today?");
        assertThat(n).doesNotContain("?").doesNotContain("'");
        // 停用词 "the" 整体去除(注意是 token 匹配,不要因 substring 误判)
        assertThat(java.util.Arrays.asList(n.split(" "))).doesNotContain("the");
        // 主要语义词保留
        assertThat(n).contains("weather").contains("tokyo").contains("today");
    }

    @Test
    @DisplayName("中文:替换中文标点为空格 + 去停用词")
    void chineseNormalize() {
        String n = QueryNormalizer.normalize("今天天气怎么样?请告诉我!");
        assertThat(n).doesNotContain("?").doesNotContain("!").doesNotContain("的").doesNotContain("请");
        assertThat(n).contains("今天").contains("天气").contains("怎么");
    }

    @Test
    @DisplayName("时间戳/时长被去除")
    void timestampAndDurationStripped() {
        String n = QueryNormalizer.normalize("订单 1700000000 创建后 100ms 超时");
        assertThat(n).doesNotContain("1700000000").doesNotContain("100ms");
    }

    @Test
    @DisplayName("连续空白合并为单空格 + trim")
    void whitespaceCollapsed() {
        String n = QueryNormalizer.normalize("   hello    world   ");
        assertThat(n).isEqualTo("hello world");
    }

    @Test
    @DisplayName("空输入返回空串(null 也安全)")
    void emptyAndNull() {
        assertThat(QueryNormalizer.normalize(null)).isEmpty();
        assertThat(QueryNormalizer.normalize("")).isEmpty();
        assertThat(QueryNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("buildCacheKey:相同输入产出相同 key,不同输入产出不同 key")
    void cacheKeyStable() {
        String k1 = QueryNormalizer.buildCacheKey("t1", "gpt-4o", "weather", "sig-a", 0);
        String k2 = QueryNormalizer.buildCacheKey("t1", "gpt-4o", "weather", "sig-a", 0);
        String k3 = QueryNormalizer.buildCacheKey("t1", "gpt-4o", "weather", "sig-b", 0);
        assertThat(k1).isEqualTo(k2);
        assertThat(k1).isNotEqualTo(k3);
        // 16-bit hex(32 bit hashCode 的 hex 化)
        assertThat(k1).hasSize(8).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("cacheKey:不同 temperatureBucket 区分")
    void cacheKeyTemperatureDiscriminator() {
        String a = QueryNormalizer.buildCacheKey("t", "m", "q", "s", 0);
        String b = QueryNormalizer.buildCacheKey("t", "m", "q", "s", 1);
        assertThat(a).isNotEqualTo(b);
    }
}
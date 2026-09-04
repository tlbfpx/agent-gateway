package com.company.agentgateway.application.observability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PiiOutputSanitizerTest {

    private final PiiOutputSanitizer s = new PiiOutputSanitizer();

    @Test
    void 手机号打码() {
        assertThat(s.sanitize("联系 13812345678 谢")).isEqualTo("联系 138****5678 谢");
    }

    @Test
    void 身份证打码() {
        String id = "110101199003077758";
        String out = s.sanitize("身份证 " + id);
        assertThat(out).doesNotContain(id);
        assertThat(out).contains("1101**********");
    }

    @Test
    void 邮箱打码() {
        assertThat(s.sanitize("邮箱 zhang.san@corp.com")).isEqualTo("邮箱 z***@corp.com");
    }

    @Test
    void 普通文本直通() {
        assertThat(s.sanitize("你好，世界 MiniMax")).isEqualTo("你好，世界 MiniMax");
    }

    @Test
    void 空与null安全() {
        assertThat(s.sanitize("")).isEmpty();
        assertThat(s.sanitize(null)).isNull();
    }

    @Test
    void 逐chunk安全_无跨块状态() {
        s.sanitize("13812345678"); // 第一次调用不影响第二次
        assertThat(s.sanitize("13812345678")).isEqualTo("138****5678");
    }
}

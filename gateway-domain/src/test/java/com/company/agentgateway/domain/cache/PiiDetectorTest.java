package com.company.agentgateway.domain.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiDetectorTest {

    @Test
    @DisplayName("身份证(18 位)被检测")
    void idCard() {
        assertThat(PiiDetector.containsPii("我身份证 11010519491231002X")).isTrue();
    }

    @Test
    @DisplayName("中国大陆手机号被检测")
    void mobileCn() {
        assertThat(PiiDetector.containsPii("call 13800138000 for support")).isTrue();
    }

    @Test
    @DisplayName("银行卡(连续 16 位)被检测")
    void bankCard() {
        assertThat(PiiDetector.containsPii("card 6222021234567890 default")).isTrue();
    }

    @Test
    @DisplayName("邮箱被检测")
    void email() {
        assertThat(PiiDetector.containsPii("contact alice@example.com today")).isTrue();
    }

    @Test
    @DisplayName("IPv4 被检测")
    void ipv4() {
        assertThat(PiiDetector.containsPii("server 192.168.1.1 is down")).isTrue();
    }

    @Test
    @DisplayName("普通文本不被误判")
    void normalText() {
        assertThat(PiiDetector.containsPii("今天天气怎么样")).isFalse();
        assertThat(PiiDetector.containsPii("how to deploy")).isFalse();
        assertThat(PiiDetector.containsPii("订单 123 状态"))  // 3 位数不算银行卡
                .isFalse();
    }

    @Test
    @DisplayName("null / 空安全")
    void nullSafe() {
        assertThat(PiiDetector.containsPii(null)).isFalse();
        assertThat(PiiDetector.containsPii("")).isFalse();
    }
}
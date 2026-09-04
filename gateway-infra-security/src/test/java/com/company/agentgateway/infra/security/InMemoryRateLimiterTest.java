package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private static final AuthPrincipal P = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"), Set.of(), Set.of(), AuthChannel.API_KEY);

    @Test
    void 未超限全部放行() {
        var limiter = new InMemoryRateLimiter(10, 5, 3, 2, 10_000);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(P, "sk-1", "agent")).isNull();
            limiter.release("agent"); // 每次调用结束释放并发槽（真实语义）
        }
    }

    @Test
    void 租户QPS超限返回原因() {
        var limiter = new InMemoryRateLimiter(2, 0, 0, 0, 0);
        limiter.tryAcquire(P, "k", null);
        limiter.tryAcquire(P, "k", null);
        assertThat(limiter.tryAcquire(P, "k", null)).isEqualTo("tenant-qps");
    }

    @Test
    void 用户QPS与KeyQPS独立计数() {
        var limiter = new InMemoryRateLimiter(0, 1, 1, 0, 0);
        assertThat(limiter.tryAcquire(P, "sk-a", null)).isNull();
        assertThat(limiter.tryAcquire(P, "sk-a", null)).isEqualTo("user-qps");
        // 换 key：user 维度已超（同用户）
        assertThat(limiter.tryAcquire(P, "sk-b", null)).isEqualTo("user-qps");
    }

    @Test
    void Agent并发超限_释放后恢复() {
        var limiter = new InMemoryRateLimiter(0, 0, 0, 1, 0);
        assertThat(limiter.tryAcquire(P, "k", "agent-x")).isNull();
        assertThat(limiter.tryAcquire(P, "k", "agent-x")).isEqualTo("agent-concurrency");
        limiter.release("agent-x");
        assertThat(limiter.tryAcquire(P, "k", "agent-x")).isNull();
    }

    @Test
    void token日预算_不足拒绝() {
        var limiter = new InMemoryRateLimiter(0, 0, 0, 0, 100);
        assertThat(limiter.tryAcquireTokens(new TenantId("t1"), 60)).isTrue();
        assertThat(limiter.tryAcquireTokens(new TenantId("t1"), 60)).isFalse(); // 60+60>100
        assertThat(limiter.tryAcquireTokens(new TenantId("t2"), 60)).isTrue();  // 租户隔离
    }

    @Test
    void 预算0表示不限() {
        var limiter = new InMemoryRateLimiter(0, 0, 0, 0, 0);
        assertThat(limiter.tryAcquireTokens(new TenantId("t1"), 999_999_999L)).isTrue();
    }
}

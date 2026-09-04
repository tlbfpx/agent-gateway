package com.company.agentgateway.replay.sdk;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.time.Duration;

/**
 * Resilience4j 适配的 Retry 装饰器(Sprint 2 P5.3):
 * 用 {@code Retry.decorateCheckedSupplier} 提供与 {@link CallbackVerifier#verifyWithRetry}
 * 同语义的指数退避重试。
 *
 * <h2>使用场景</h2>
 * <p>当下游消费方已引入 Resilience4j 生态(熔断/限流/重试统一治理)时,可直接复用
 * {@code RetryRegistry} 配置;本类做 SDK ↔ R4j 的桥接。
 *
 * <h2>与 CallbackVerifier.RetryPolicy 的区别</h2>
 * <ul>
 *   <li>{@code RetryPolicy}:SDK 内置,零额外依赖,简单场景</li>
 *   <li>{@code Resilience4jRetry}:与 Spring Boot / Micronaut 生态统一监控,支持 circuit breaker 联动</li>
 * </ul>
 *
 * <p>两者行为兼容:重试策略一致(3 attempts / 100ms-5s 指数退避 / 25% 抖动)。
 *
 * <p><b>依赖</b>:需要 resilience4j-retry 2.2.0+;SDK pom 已声明为 {@code <optional>true</optional>},
 * 部署侧未引入时会抛 ClassNotFoundException(明确报错,避免静默 fallback)。
 */
public final class Resilience4jRetry {

    private Resilience4jRetry() {}

    /**
     * 用 Resilience4j 包装 SignProvider。
     *
     * @param provider  外部签名源(HSM/KMS)
     * @param timestamp 验证用时间戳
     * @param method    HTTP method
     * @param path      请求路径
     * @param body      请求体
     * @return 签名 hex;重试用尽抛 RuntimeException
     */
    public static String executeWithRetry(CallbackVerifier.SignProvider provider,
                                           long timestamp, String method, String path, String body) {
        Retry retry = Retry.of("callback-verify",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                                Duration.ofMillis(100), 2.0, 0.25))
                        .retryOnException(Resilience4jRetry::isTransient)
                        .build());
        // 用 decorateSupplier(unchecked)避免 decorateCheckedSupplier.get() 的 throws Throwable
        return Retry.decorateSupplier(retry,
                () -> provider.expectedSignature(timestamp, method, path, body)).get();
    }

    /**
     * 用 Resilience4jRegistry 共享配置(供全局 resilience4j 配置中心统一管理)。
     */
    public static String executeWithRetry(CallbackVerifier.SignProvider provider, long timestamp,
                                           String method, String path, String body,
                                           RetryRegistry registry) {
        Retry retry = registry.retry("callback-verify");
        return Retry.decorateSupplier(retry,
                () -> provider.expectedSignature(timestamp, method, path, body)).get();
    }

    /** 与 SDK 内置 RetryPolicy.isTransient 一致(沿 cause 链查找)。 */
    static boolean isTransient(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String name = cur.getClass().getName();
            if (name.contains("IOException") || name.contains("SocketTimeout")
                    || name.contains("ConnectException") || name.contains("Network")
                    || name.contains("Timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
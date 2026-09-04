package com.company.agentgateway.replay.sdk;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.time.Duration;

/**
 * P5.3 — Resilience4j {@link RetryRegistry} 工厂。
 *
 * <p>把 SDK 内置的 {@link CallbackVerifier.RetryPolicy} 翻译成
 * Resilience4j 的 {@link RetryConfig},让 SDK ↔ R4j 重试策略同步,
 * 下游消费方一行接入。
 *
 * <h2>典型用法</h2>
 * <h3>纯 JDK 消费方</h3>
 * <pre>
 *   RetryRegistry registry = RetryRegistries.callbackVerifyDefault();
 *   String sig = Resilience4jRetry.executeWithRetry(
 *           provider, ts, "POST", "/v1/cb", body, registry);
 * </pre>
 *
 * <h3>Spring Boot 接入(共享配置 / Prometheus 指标)</h3>
 * <pre>
 *   &#064;Configuration
 *   public class ReplayConfig {
 *       &#064;Bean
 *       public RetryRegistry callbackVerifyRetryRegistry() {
 *           return RetryRegistries.callbackVerifyDefault();
 *       }
 *   }
 * </pre>
 *
 * <p><b>行为兼容</b>:SDK {@code DEFAULT_RETRY}(3 / 100ms / 5s / 2x / 25% 抖动)
 * 与 {@link #callbackVerifyDefault()} 返回的 {@link RetryRegistry} 配置一致,
 * 见 {@link RetryRegistriesTest#defaultMatchesSdkDefaultRetry()}。
 */
public final class RetryRegistries {

    /** Resilience4j retry instance 名 — SDK 内固定,便于跨调用复用 + metrics 聚合。 */
    public static final String CALLBACK_VERIFY = "callback-verify";

    private RetryRegistries() {}

    /**
     * 与 {@link CallbackVerifier#DEFAULT_RETRY} 行为一致的 {@link RetryRegistry}。
     *
     * <ul>
     *   <li>maxAttempts = 3</li>
     *   <li>initialBackoff = 100ms,maxBackoff = 5s,multiplier = 2.0,jitter = 25%</li>
     *   <li>仅对瞬时异常(IO / Socket / Connect / Timeout)重试</li>
     * </ul>
     */
    public static RetryRegistry callbackVerifyDefault() {
        return RetryRegistry.of(buildConfig(CallbackVerifier.DEFAULT_RETRY));
    }

    /**
     * 用 SDK {@link CallbackVerifier.RetryPolicy} 构造 {@link RetryRegistry}。
     * 让用户在不引入 R4j 配置 DSL 的前提下,用熟悉的 SDK record 表达重试策略。
     */
    public static RetryRegistry callbackVerifyFrom(CallbackVerifier.RetryPolicy policy) {
        return RetryRegistry.of(buildConfig(policy));
    }

    private static RetryConfig buildConfig(CallbackVerifier.RetryPolicy policy) {
        // Resilience4j 2.2.0 的 IntervalFunction 无 withMaxWaitInterval — 用 lambda 包装截断
        IntervalFunction base = IntervalFunction.ofExponentialRandomBackoff(
                Duration.ofMillis(policy.initialBackoffMs()),
                policy.multiplier(),
                policy.jitterRatio());
        long maxBackoffMs = policy.maxBackoffMs();
        IntervalFunction capped = attemptNr -> Math.min(base.apply(attemptNr), maxBackoffMs);
        return RetryConfig.custom()
                .maxAttempts(policy.maxAttempts())
                .intervalFunction(capped)
                .retryOnException(Resilience4jRetry::isTransient)
                .build();
    }
}
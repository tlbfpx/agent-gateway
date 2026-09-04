package com.company.agentgateway.domain.billing;

/**
 * Stripe Checkout 流程异常（spec §21.7）。
 *
 * <p>由 {@link TopUpPort} 的实现抛出，被上游 HTTP 层映射为 4xx 错误。
 * 错误码命名：{@code GW-STRIPE-xxxx}，由调用方按 message 前缀解析。
 */
public class StripeCheckoutException extends RuntimeException {

    public StripeCheckoutException(String message) {
        super(message);
    }

    public StripeCheckoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 校验失败（参数非法 / 缺失）。 */
    public static StripeCheckoutException validation(String message) {
        return new StripeCheckoutException("GW-STRIPE-VALIDATION: " + message);
    }

    /** 资源不存在（vkId 未注册）。 */
    public static StripeCheckoutException notFound(String message) {
        return new StripeCheckoutException("GW-STRIPE-NOT-FOUND: " + message);
    }
}
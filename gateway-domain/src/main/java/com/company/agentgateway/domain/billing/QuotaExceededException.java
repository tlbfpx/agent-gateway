package com.company.agentgateway.domain.billing;

/**
 * 配额超限异常（spec §21.4 · D2 GW-QUOTA-006）。
 *
 * <p>由 {@code QuotaGate.check()} 抛出，被上游 HTTP 层映射为 429/403 + 错误码。
 */
public class QuotaExceededException extends RuntimeException {

    private final String errorCode;

    public QuotaExceededException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public QuotaExceededException(String errorCode, String message, Throwable cause) {
        super(errorCode + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

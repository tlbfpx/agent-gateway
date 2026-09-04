package com.company.agentgateway.domain.billing;

/**
 * 预算配置异常（spec §GW-QUOTA-004）。
 *
 * <p>由 {@code QuotaPolicy} 校验或 SUSPEND 写入失败抛出，被上游 HTTP 层映射为 400 + 错误码。
 */
public class BudgetConfigurationException extends RuntimeException {

    private final String errorCode;

    public BudgetConfigurationException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

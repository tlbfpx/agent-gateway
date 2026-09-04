package com.company.agentgateway.domain.shared;

public record TenantId(String value) {
    public TenantId {
        IdValidation.requireNonBlank(value);
    }
}

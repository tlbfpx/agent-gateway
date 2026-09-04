package com.company.agentgateway.domain.shared;

public record ApiKeyId(String value) {
    public ApiKeyId {
        IdValidation.requireNonBlank(value);
    }
}

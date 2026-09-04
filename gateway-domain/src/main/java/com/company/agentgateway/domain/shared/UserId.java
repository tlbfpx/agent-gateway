package com.company.agentgateway.domain.shared;

public record UserId(String value) {
    public UserId {
        IdValidation.requireNonBlank(value);
    }
}

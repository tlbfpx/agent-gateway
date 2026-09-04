package com.company.agentgateway.domain.shared;

public record SessionId(String value) {
    public SessionId {
        IdValidation.requireNonBlank(value);
    }
}

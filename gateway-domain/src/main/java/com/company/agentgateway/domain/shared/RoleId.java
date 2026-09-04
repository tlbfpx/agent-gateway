package com.company.agentgateway.domain.shared;

public record RoleId(String value) {
    public RoleId {
        IdValidation.requireNonBlank(value);
    }
}

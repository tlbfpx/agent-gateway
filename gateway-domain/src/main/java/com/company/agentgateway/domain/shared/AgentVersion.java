package com.company.agentgateway.domain.shared;

public record AgentVersion(String value) {
    public AgentVersion {
        IdValidation.requireNonBlank(value);
    }
}

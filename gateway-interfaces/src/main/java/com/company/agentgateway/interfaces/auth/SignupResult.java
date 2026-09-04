package com.company.agentgateway.interfaces.auth;

/** Signup 返回（spec 2026-09-04 §self-serve-signup §3.3）。 */
public record SignupResult(String tenantId, String email, String adminToken) {}
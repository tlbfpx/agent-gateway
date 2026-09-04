package com.company.agentgateway.application.admin.auth.jwt;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;

class JwtServiceTest {

    private JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService("test-secret-must-be-at-least-16-chars", 3600);
    }

    @Test
    void issue_returnsThreePartToken() {
        String token = jwt.issue(42L, AdminRole.ADMIN, "au");
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void verify_extractsClaims() {
        String token = jwt.issue(42L, AdminRole.ADMIN, "au");
        Map<String, Object> claims = jwt.verify(token);
        assertEquals("42", claims.get("sub"));
        assertEquals("ADMIN", claims.get("role"));
        assertEquals("au", claims.get("tenantId"));
        assertNotNull(claims.get("iat"));
        assertNotNull(claims.get("exp"));
    }

    @Test
    void verify_rejectsTamperedSignature() {
        String token = jwt.issue(1L, AdminRole.ADMIN, "au");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(SecurityException.class, () -> jwt.verify(tampered));
    }

    @Test
    void verify_rejectsExpiredToken() throws InterruptedException {
        // TTL=2s + sleep 3s 留充足余量,避免 verify.sh 并行测试环境下 CPU 调度延迟
        JwtService shortJwt = new JwtService("test-secret-must-be-at-least-16-chars", 2);
        String token = shortJwt.issue(1L, AdminRole.ADMIN, "au");
        Thread.sleep(3000);
        assertThrows(SecurityException.class, () -> shortJwt.verify(token));
    }

    @Test
    void verify_rejectsMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> jwt.verify("not.a.valid.token.here"));
        assertThrows(IllegalArgumentException.class, () -> jwt.verify("only.two"));
        assertThrows(IllegalArgumentException.class, () -> jwt.verify(""));
    }

    @Test
    void differentSecrets_produceDifferentTokens() {
        JwtService other = new JwtService("another-secret-also-16-plus-chars", 3600);
        String t1 = jwt.issue(1L, AdminRole.VIEWER, "au");
        String t2 = other.issue(1L, AdminRole.VIEWER, "au");
        assertTrue(!t1.equals(t2));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short", 3600));
    }
}
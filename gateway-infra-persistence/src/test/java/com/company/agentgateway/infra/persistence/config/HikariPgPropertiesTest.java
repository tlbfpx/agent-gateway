package com.company.agentgateway.infra.persistence.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HikariPgProperties 单测。
 * 注:HikariDataSource 本身需要真实 PG 连接,只测 properties 字段绑定。
 */
class HikariPgPropertiesTest {

    @Test
    void properties_defaultPoolSize_is20() {
        HikariPgConfig.HikariPgProperties p = new HikariPgConfig.HikariPgProperties();
        assertEquals(20, p.getPoolSize());
        assertNotNull(p);
    }

    @Test
    void properties_settersRoundTrip() {
        HikariPgConfig.HikariPgProperties p = new HikariPgConfig.HikariPgProperties();
        p.setUrl("jdbc:postgresql://localhost:5432/test");
        p.setUsername("agent");
        p.setPassword("secret");
        p.setPoolSize(50);
        assertEquals("jdbc:postgresql://localhost:5432/test", p.getUrl());
        assertEquals("agent", p.getUsername());
        assertEquals("secret", p.getPassword());
        assertEquals(50, p.getPoolSize());
    }
}

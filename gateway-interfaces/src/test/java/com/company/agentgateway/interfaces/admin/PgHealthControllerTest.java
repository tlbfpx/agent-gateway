package com.company.agentgateway.interfaces.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PgHealthController 单测。
 * 注：H2 DriverManagerDataSource 在 Spring 6 + H2 2.x 下会立即关闭连接,
 * 单元测只覆盖 no-DataSource 路径(503 + reason);
 * 真实连接路径由 Round 16+ 集成测试(Testcontainers PG)覆盖。
 */
class PgHealthControllerTest {

    @Test
    void noDataSource_returns503() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> emptyProvider = new ObjectProvider<>() {
            @Override
            public DataSource getIfAvailable() { return null; }
        };
        PgHealthController controller = new PgHealthController(emptyProvider);
        ResponseEntity<Map<String, Object>> r = controller.pg();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        assertEquals("DOWN", r.getBody().get("status"));
        assertTrue(r.getBody().get("reason").toString().contains("no DataSource"));
    }
}


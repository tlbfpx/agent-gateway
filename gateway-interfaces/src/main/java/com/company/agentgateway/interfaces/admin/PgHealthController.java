package com.company.agentgateway.interfaces.admin;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pg 健康检查端点（spec 2026-09-02 §hikari-pool §4）。
 *
 * <p>{@code GET /v1/admin/health/pg} —— 返回连接池状态 + ping 延迟。
 */
@RestController
@RequestMapping("/v1/admin/health")
public class PgHealthController {

    private final ObjectProvider<DataSource> dataSourceProvider;

    public PgHealthController(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @GetMapping("/pg")
    public ResponseEntity<Map<String, Object>> pg() {
        Map<String, Object> out = new LinkedHashMap<>();
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null) {
            out.put("status", "DOWN");
            out.put("reason", "no DataSource bean");
            return ResponseEntity.status(503).body(out);
        }
        long t0 = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            boolean valid = c.isValid(1);
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;
            out.put("status", valid ? "UP" : "DOWN");
            out.put("latencyMs", latencyMs);

            // 如果是 HikariDataSource,附加池指标
            if (ds instanceof HikariDataSource hds) {
                HikariPoolMXBean pool = hds.getHikariPoolMXBean();
                Map<String, Object> poolMetrics = new LinkedHashMap<>();
                poolMetrics.put("activeConnections", pool.getActiveConnections());
                poolMetrics.put("idleConnections", pool.getIdleConnections());
                poolMetrics.put("totalConnections", pool.getTotalConnections());
                poolMetrics.put("threadsAwaitingConnection", pool.getThreadsAwaitingConnection());
                out.put("pool", poolMetrics);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            out.put("status", "DOWN");
            out.put("error", e.getMessage());
            return ResponseEntity.status(503).body(out);
        }
    }
}
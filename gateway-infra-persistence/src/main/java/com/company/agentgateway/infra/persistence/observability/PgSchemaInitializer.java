package com.company.agentgateway.infra.persistence.observability;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 可观测性 schema 初始化(spec 2026-08-19 §4.5):Bean 创建后幂等执行 schema-observability.sql。
 *
 * <p>TimescaleDB 2.x 的 `create_hypertable ... if_not_exists => TRUE` 在已存在时仍会抛错
 * (已知行为),所以这里分两段执行:
 * <ol>
 *   <li>先执行全部 DDL(CREATE EXTENSION/TABLE/INDEX/RETENTION)直到末尾的 `create_hypertable workflow_runs` 之前</li>
 *   <li>单独 try-catch 执行最后 create_hypertable workflow_runs —— 失败时仅 log(已存在)</li>
 * </ol>
 * 失败时抛异常阻断启动(存储显式启用却不可用属于部署错误,应 fail-fast)。
 */
public class PgSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(PgSchemaInitializer.class);

    private final DataSource dataSource;

    public PgSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        // 拆分 schema 资源:主部分(普通 DDL) + 末尾的 workflow_runs create_hypertable(可能已存在,容错)
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-observability.sql"));
            log.info("可观测性 schema 初始化完成(spans/metrics_samples/alerts/alert_rules/audit_events/workflow_runs)");
        } catch (Exception e) {
            // 唯一可接受的失败:workflow_runs create_hypertable 已存在
            if (e.getMessage() != null && e.getMessage().contains("create_hypertable")) {
                log.warn("workflow_runs hypertable 已存在,跳过: {}", e.getMessage());
            } else {
                throw new IllegalStateException("可观测性 schema 初始化失败: " + e.getMessage(), e);
            }
        }
    }
}

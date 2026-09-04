package com.company.agentgateway.infra.persistence.billing;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 计费/配额/预算 + RBAC schema 初始化（add-pg-persistence）：
 * Bean 创建后幂等执行 schema-billing-rbac.sql（全部 CREATE IF NOT EXISTS，可重复执行）。
 *
 * <p>与 {@link PgSchemaInitializer}（observability schema）相互独立、各自幂等。
 * 失败 fail-fast（存储显式启用却不可用属于部署错误）。
 */
public class PgBillingRbacSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(PgBillingRbacSchemaInitializer.class);

    private final DataSource dataSource;

    public PgBillingRbacSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-billing-rbac.sql"));
            log.info("计费/RBAC schema 初始化完成(billing_records/budgets/quota_counters/rbac_roles/rbac_role_bindings)");
        } catch (Exception e) {
            throw new IllegalStateException("计费/RBAC schema 初始化失败: " + e.getMessage(), e);
        }
    }
}

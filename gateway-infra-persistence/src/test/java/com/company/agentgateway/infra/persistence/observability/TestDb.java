package com.company.agentgateway.infra.persistence.observability;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 共享 Testcontainers 容器(spec P0):多个 IT 共用一个 PG 实例,避免每个 IT 启动容器慢。
 *
 * <p>用法:DataSource ds = TestDb.connect();
 * 默认起 TimescaleDB 容器(本地 maven 拿不到 Docker 时回退外部 PG_URL);
 * 容器在 JVM 关闭时 stop。
 */
public final class TestDb {

    private static final AtomicReference<PostgreSQLContainer<?>> CONTAINER = new AtomicReference<>();
    private static volatile DataSource ds;

    private TestDb() {}

    public static synchronized DataSource connect() {
        if (ds != null) return ds;
        String url = System.getenv("PG_URL");
        if (url == null || url.isBlank()) {
            PostgreSQLContainer<?> container = CONTAINER.get();
            if (container == null) {
                container = new PostgreSQLContainer<>(
                        DockerImageName.parse("timescale/timescaledb:latest-pg16")
                                .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName("agentgateway_it")
                        .withUsername("test")
                        .withPassword("test");
                container.start();
                CONTAINER.set(container);
                Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
            }
            url = container.getJdbcUrl();
        }
        ds = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("PG_USER", "agentgateway"),
                System.getenv().getOrDefault("PG_PASSWORD", "agentgateway"));
        // 全量 init schema(幂等,后续 IT 共享)
        new PgSchemaInitializer(ds).initialize();
        return ds;
    }
}
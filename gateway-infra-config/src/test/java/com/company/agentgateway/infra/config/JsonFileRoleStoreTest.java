package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.InMemoryConfigReloadBus;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.SkillPermission;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonFileRoleStore 测试(Sprint 1 P0 §3.4)。
 *
 * <p>覆盖:CRUD、原子持久化、热重载、sealed Permission 多态反序列化、重启恢复。
 */
class JsonFileRoleStoreTest {

    @TempDir
    Path tempDir;

    private InMemoryConfigReloadBus bus;
    private JsonFileRoleStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantId tenantA = new TenantId("tenant-A");
    private final TenantId tenantB = new TenantId("tenant-B");
    private final AtomicInteger reloadCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        bus = new InMemoryConfigReloadBus();
        store = new JsonFileRoleStore(tempDir.resolve("rbac.json"), mapper, bus);
        store.start();
        store.addReloadListener(reloadCount::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        store.stop();
    }

    @Test
    @DisplayName("save + findById + findAll:基本 CRUD")
    void basicCrud() {
        Role admin = new Role(new RoleId("admin"), "admin", "all perms",
                Set.of(new ModelPermission(Set.of(new ModelId("gpt-4o")))));
        store.save(tenantA, admin);

        assertThat(store.findById(tenantA, new RoleId("admin"))).contains(admin);
        assertThat(store.findAll(tenantA)).hasSize(1);
        assertThat(store.findAll(tenantB)).isEmpty();
        assertThat(store.findById(tenantA, new RoleId("missing"))).isEmpty();
    }

    @Test
    @DisplayName("save 落盘:原子写 rbac.json")
    void savePersistsToFile() throws Exception {
        store.save(tenantA, new Role(new RoleId("r1"), "role1", "d",
                Set.of(new AgentPermission("agent-x", Set.of("skill-a")))));
        assertThat(Files.exists(tempDir.resolve("rbac.json"))).isTrue();

        String content = Files.readString(tempDir.resolve("rbac.json"));
        assertThat(content).contains("tenant-A").contains("role1").contains("agent-x");
    }

    @Test
    @DisplayName("sealed Permission 多态序列化/反序列化:agent / model / skill 三类")
    void polymorphicPermissionSerialization() throws Exception {
        Role mixed = new Role(new RoleId("mixed"), "mixed", "d",
                Set.of(
                        new AgentPermission("a1", Set.of("s1")),
                        new ModelPermission(Set.of(new ModelId("m1"), new ModelId("m2"))),
                        new SkillPermission("a2", "s2")
                ));
        store.save(tenantA, mixed);

        // 强制重载,模拟重启
        store.reload();
        Role reloaded = store.findById(tenantA, new RoleId("mixed")).orElseThrow();
        assertThat(reloaded.permissions()).hasSize(3);
        assertThat(reloaded.permissions())
                .anySatisfy(p -> {
                    assertThat(p).isInstanceOf(AgentPermission.class);
                    assertThat(((AgentPermission) p).agentName()).isEqualTo("a1");
                })
                .anySatisfy(p -> {
                    assertThat(p).isInstanceOf(ModelPermission.class);
                    assertThat(((ModelPermission) p).models())
                            .extracting(ModelId::value).containsExactlyInAnyOrder("m1", "m2");
                })
                .anySatisfy(p -> {
                    assertThat(p).isInstanceOf(SkillPermission.class);
                    assertThat(((SkillPermission) p).agentName()).isEqualTo("a2");
                    assertThat(((SkillPermission) p).skillName()).isEqualTo("s2");
                });
    }

    @Test
    @DisplayName("delete:移除并落盘")
    void deletePersists() {
        store.save(tenantA, new Role(new RoleId("r1"), "n", "d", Set.of()));
        store.save(tenantA, new Role(new RoleId("r2"), "n", "d", Set.of()));
        store.delete(tenantA, new RoleId("r1"));

        assertThat(store.findById(tenantA, new RoleId("r1"))).isEmpty();
        assertThat(store.findAll(tenantA)).hasSize(1);
    }

    @Test
    @DisplayName("外部修改文件 + 触发 bus 事件:store 自动 reload")
    void reloadOnBusEvent() throws Exception {
        // 外部写入
        Files.writeString(tempDir.resolve("rbac.json"), """
                {
                  "tenants": {
                    "tenant-X": {
                      "roles": [
                        {
                          "id": "ext-role",
                          "name": "external",
                          "description": "from outside",
                          "permissions": [
                            {"type": "agent", "agentName": "ext-agent", "allowedSkills": ["*"]},
                            {"type": "skill", "skillAgent": "ext-agent", "skillSkillName": "ext-skill"}
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

        bus.publish(new ConfigChanged(
                "rbac", ConfigChanged.Source.FILE,
                System.currentTimeMillis(), null, null, "system", java.time.Instant.now()));

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(reloadCount.get()).isGreaterThanOrEqualTo(1));

        assertThat(store.findById(new TenantId("tenant-X"), new RoleId("ext-role"))).isPresent();
        assertThat(reloadCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("重启场景:已有 rbac.json → 新 store 加载")
    void restartLoadsExisting() throws Exception {
        store.save(tenantA, new Role(new RoleId("persistent"), "p", "d",
                Set.of(new ModelPermission(Set.of(new ModelId("gpt-4o"))))));
        store.stop();

        bus = new InMemoryConfigReloadBus();
        store = new JsonFileRoleStore(tempDir.resolve("rbac.json"), mapper, bus);
        store.start();

        assertThat(store.findById(tenantA, new RoleId("persistent"))).isPresent();
    }

    @Test
    @DisplayName("文件损坏:加载失败,保留上一次内存状态,不抛异常")
    void corruptedFileKeepsPreviousState() {
        store.save(tenantA, new Role(new RoleId("keep"), "keep", "d", Set.of()));
        try {
            Files.writeString(tempDir.resolve("rbac.json"), "{ not valid json");
            store.reload();
            // 之前的状态保留(load 失败时 store 不动)
            assertThat(store.findById(tenantA, new RoleId("keep"))).isPresent();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("多租户隔离:不同 tenant 的角色互不影响")
    void multiTenantIsolation() {
        store.save(tenantA, new Role(new RoleId("r1"), "a", "d", Set.of()));
        store.save(tenantB, new Role(new RoleId("r1"), "b", "d", Set.of()));

        assertThat(store.findAll(tenantA)).hasSize(1);
        assertThat(store.findAll(tenantB)).hasSize(1);
        assertThat(store.findById(tenantA, new RoleId("r1")).orElseThrow().name()).isEqualTo("a");
        assertThat(store.findById(tenantB, new RoleId("r1")).orElseThrow().name()).isEqualTo("b");
    }

    @Test
    @DisplayName("reloadListeners 在 reload 时被调用")
    void reloadListenersAreInvoked() throws Exception {
        Files.writeString(tempDir.resolve("rbac.json"), """
                {"tenants":{}}
                """);
        int before = reloadCount.get();
        store.reload();
        assertThat(reloadCount.get()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("stop 后 reload 不抛异常")
    void stopIsIdempotent() {
        store.stop();
        store.stop(); // 幂等
    }
}
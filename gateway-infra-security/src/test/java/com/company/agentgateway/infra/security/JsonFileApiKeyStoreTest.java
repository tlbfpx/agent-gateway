package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileApiKeyStoreTest {

    @TempDir
    Path tmp;

    private ApiKeyStore.ApiKeyBinding binding() {
        return new ApiKeyStore.ApiKeyBinding(
                new TenantId("t1"), new UserId("u1"),
                Set.of(new AgentGrant("echo-agent", Set.of())),
                Set.of(new ModelId("minimax-abab6.5s-chat")),
                false);
    }

    @Test
    void 签发后重启仍在_持久化生效() {
        Path file = tmp.resolve("keys.json");
        JsonFileApiKeyStore s1 = new JsonFileApiKeyStore(file);
        s1.register("sk-test-1", binding());
        assertThat(s1.findByKey("sk-test-1")).isPresent();

        // 模拟重启：新实例从同一文件加载
        JsonFileApiKeyStore s2 = new JsonFileApiKeyStore(file);
        assertThat(s2.findByKey("sk-test-1")).isPresent();
        assertThat(s2.findByKey("sk-test-1").get().tenant().value()).isEqualTo("t1");
        assertThat(s2.listKeys()).contains("sk-test-1");
    }

    @Test
    void 吊销后重启仍不存在() {
        Path file = tmp.resolve("keys.json");
        JsonFileApiKeyStore s1 = new JsonFileApiKeyStore(file);
        s1.register("sk-gone", binding());
        s1.revoke("sk-gone");
        assertThat(s1.findByKey("sk-gone")).isEmpty();

        JsonFileApiKeyStore s2 = new JsonFileApiKeyStore(file);
        assertThat(s2.findByKey("sk-gone")).isEmpty();
    }

    @Test
    void 文件不存在时空表启动() {
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(tmp.resolve("nonexistent.json"));
        assertThat(store.listKeys()).isEmpty();
        assertThat(store.findByKey("any")).isEmpty();
    }

    @Test
    void 文件损坏时空表启动不抛异常() throws Exception {
        Path file = tmp.resolve("broken.json");
        java.nio.file.Files.writeString(file, "not valid json!!!");
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(file);
        assertThat(store.listKeys()).isEmpty();
    }

    @Test
    void 文件含tenants字段_重启后多租户授权仍生效() throws Exception {
        // 文件写一个 tenants=[t1, t2] 的 key（模拟已落盘的多租户授权）
        Path file = tmp.resolve("keys.json");
        java.nio.file.Files.writeString(file, """
                [ {"key":"sk-multi","tenant":"t1","user":"u1",
                   "agentGrants":["echo-agent"],
                   "allowedModels":["minimax-abab6.5s-chat"],
                   "revoked":false,
                   "tenants":["t1","t2"]} ]
                """);
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(file);
        ApiKeyStore.ApiKeyBinding b = store.findByKey("sk-multi").orElseThrow();
        // 多租户授权列表必须被加载（不是仅 {t1} 兜底）
        assertThat(b.tenants()).contains(new TenantId("t1"), new TenantId("t2"));
        assertThat(b.allowsTenant(new TenantId("t2"))).isTrue();
        assertThat(b.allowsTenant(new TenantId("t3"))).isFalse();
    }

    @Test
    void 过期key_认证时被拒绝() {
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(tmp.resolve("keys.json"));
        store.register("sk-expired", new ApiKeyStore.ApiKeyBinding(
                new TenantId("t1"), new UserId("u1"), Set.of(), Set.of(), false,
                Set.of(new TenantId("t1")), java.time.Instant.now().minusSeconds(60)));
        store.register("sk-alive", new ApiKeyStore.ApiKeyBinding(
                new TenantId("t1"), new UserId("u1"), Set.of(), Set.of(), false,
                Set.of(new TenantId("t1")), java.time.Instant.now().plusSeconds(3600)));
        assertThat(store.findByKey("sk-expired")).isEmpty();
        assertThat(store.findByKey("sk-alive")).isPresent();
    }

    @Test
    void expiresAt持久化_重启后仍生效() throws Exception {
        Path file = tmp.resolve("keys.json");
        JsonFileApiKeyStore s1 = new JsonFileApiKeyStore(file);
        java.time.Instant exp = java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        s1.register("sk-ttl", new ApiKeyStore.ApiKeyBinding(
                new TenantId("t1"), new UserId("u1"), Set.of(), Set.of(), false,
                Set.of(new TenantId("t1")), exp));

        JsonFileApiKeyStore s2 = new JsonFileApiKeyStore(file);
        assertThat(s2.findByKey("sk-ttl").get().expiresAt()).isEqualTo(exp);
    }

    @Test
    void 无expiresAt的旧key_永不过期() throws Exception {
        Path file = tmp.resolve("keys.json");
        java.nio.file.Files.writeString(file, """
                [ {"key":"sk-forever","tenant":"t1","user":"u1","revoked":false} ]
                """);
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(file);
        assertThat(store.findByKey("sk-forever")).isPresent();
        assertThat(store.findByKey("sk-forever").get().expiresAt()).isNull();
    }

    @Test
    void 文件缺tenants字段_回退为单租户_主租户在列表内() throws Exception {
        // 一期历史 key 文件不含 tenants 字段——加载后等价 {tenant}，allowTenant(主租户)=true
        Path file = tmp.resolve("keys.json");
        java.nio.file.Files.writeString(file, """
                [ {"key":"sk-legacy","tenant":"t1","user":"u1",
                   "agentGrants":["echo-agent"],
                   "allowedModels":["minimax-abab6.5s-chat"],
                   "revoked":false} ]
                """);
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(file);
        ApiKeyStore.ApiKeyBinding b = store.findByKey("sk-legacy").orElseThrow();
        assertThat(b.tenants()).containsExactly(new TenantId("t1"));
        assertThat(b.allowsTenant(new TenantId("t1"))).isTrue();
        assertThat(b.allowsTenant(new TenantId("t2"))).isFalse();
    }
}

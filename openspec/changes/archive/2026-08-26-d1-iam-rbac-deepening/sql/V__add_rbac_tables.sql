-- D1 RBAC 深化 · 二期 JPA 落地用 SQL 草案（design §4.1）
-- 一期 InMemory 实现不需此 SQL；本文件存档待二期使用。
-- V 号由二期 change 决定（须查最大未占用 V<n>）。

-- rbac_role：租户维度角色定义
CREATE TABLE rbac_role (
    id           VARCHAR(64)  NOT NULL,                       -- RoleId.value() 形式："r-<ulid>"
    tenant_id    VARCHAR(64)  NOT NULL,                       -- TenantId.value()
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(256) NOT NULL DEFAULT '',
    permissions  JSONB        NOT NULL,                       -- Set<Permission> 序列化 [{kind:"agent", agentName:"...", allowedSkills:[...]}, ...]
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT rbac_role_permissions_size_chk CHECK (jsonb_array_length(permissions) <= 100)
);
CREATE INDEX idx_rbac_role_tenant ON rbac_role(tenant_id);

-- rbac_role_binding：用户 × 角色 多对多（租户隔离）
CREATE TABLE rbac_role_binding (
    tenant_id   VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,                         -- UserId.value()
    role_id     VARCHAR(64) NOT NULL,                         -- RoleId.value()
    actor       VARCHAR(64) NOT NULL,                         -- 绑定操作人（admin）
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT fk_rbac_role_binding_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES rbac_role(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_rbac_role_binding_user ON rbac_role_binding(tenant_id, user_id);

# Database 运维脚本(R17 #2)

## 文件

| 文件 | 用途 |
|---|---|
| `backup.sh` | 周期备份(默认 7 天保留) |
| `restore.sh` | 从备份恢复(危险操作,需 `CONFIRM=yes`) |

## 备份策略

| 维度 | 建议 |
|---|---|
| 周期 | 每天凌晨 03:00(cron) |
| 格式 | pg_dump 自定义格式(`-Fc`)+ gzip 压缩 |
| 保留 | 本地 7 天 + S3 长期归档(取消 backup.sh 注释启用) |
| 校验 | pg_restore -l 列出 schema,失败立即告警 |

## 恢复流程

```bash
# 1. 停 gateway(防写入)
docker compose stop agent-gateway

# 2. 恢复数据库
CONFIRM=yes PGHOST=db PGUSER=agentgateway PGDATABASE=agentgateway \
  ./scripts/db/restore.sh /var/backups/agent-gateway/agentgateway_20260903_030000.sql.gz

# 3. 验证数据
psql -h db -U agentgateway -d agentgateway -c "\dt"
psql -h db -U agentgateway -d agentgateway -c "SELECT count(*) FROM feedback;"

# 4. 启动 gateway(自动 Flyway baseline 校验)
docker compose start agent-gateway

# 5. 检查 /v1/admin/health/pg 端点
curl http://localhost:8080/v1/admin/health/pg
```

## Flyway baseline(R17 #2 新增)

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    locations: classpath:db/migration
    table: flyway_schema_history
    fail-on-missing-locations: true
```

- `baseline-on-migrate=true`: 已有数据的空库启动时自动建 baseline
- `baseline-version=0`: 从 V0(空)开始;V1 是首张表
- `fail-on-missing-locations=true`: classpath 路径错误立即失败

**生产部署流程**:
1. 启动 gateway 容器 → Flyway 自动 V1(feedback / admin_user / team / team_member / prompt_template / prompt_version)
2. 校验:`SELECT * FROM flyway_schema_history;` 应见 1 条 V1
3. 如失败:运行 `./scripts/db/restore.sh` 回滚到上一次成功备份

## 集成测试提示

- R15 #2 + R16 #1 + R16 #4 的 5 个 Pg Repo 走 `observability.storage.enabled=true` 路径
- 默认配置 localhost:5433,docker-compose 启动后可直接连
- 测试用 H2 内存跑(PostgreSQL mode),与生产 PG 行为一致

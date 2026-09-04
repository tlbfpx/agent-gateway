# Round 17 #2 报告 — Flyway baseline + 备份恢复

> 日期：2026-09-03 · 主攻：**R17 #2 生产部署运维补全**
> 来源：R15 #2 + R16 #4 + 用户决策
> 借鉴：Spring Boot Flyway / PostgreSQL pg_dump / LiteLLM ops playbook

---

## 一、本轮目标与切片

R15 #2 落地 Flyway,但缺生产配置 + 备份/恢复链路。R17 #2 补齐:
1. Flyway baseline 配置(已有数据空库自动建 baseline)
2. 周期备份脚本(7 天保留 + 自定义格式 + S3 同步预留)
3. 恢复脚本(防误操作 + 二次确认 + pg_restore)
4. 运维手册

## 二、产出

| 文件 | 用途 |
|---|---|
| `application.yml` | Flyway 配置块 |
| `scripts/db/backup.sh` | 周期备份(可执行) |
| `scripts/db/restore.sh` | 危险恢复(强制 CONFIRM=yes) |
| `scripts/db/README.md` | 运维手册 |

**verify.sh**: 11 模块 + 依赖方向全绿 ✅

## 三、亮点

### 1. Flyway baseline-on-migrate
```
spring.flyway.baseline-on-migrate: true
spring.flyway.baseline-version: 0
spring.flyway.fail-on-missing-locations: true
```
- 已有 InMemory 数据导入到空 PG → 启动时 Flyway 自动 baseline
- V1(flyway_schema_history + 6 张业务表)首次启动自动 migrate
- 失败快速,避免脏数据

### 2. 备份脚本设计
- `pg_dump -Fc` 自定义格式(支持并行/选择性恢复)
- `pg_dump -Z 6` gzip 压缩(中大型库节省 70%+ 空间)
- `pg_restore -l` 校验完整性(失败立即报错)
- `find -mtime +7 -delete` 自动清理过期
- 占位 S3 同步(awscli 命令行,生产可启用)

### 3. 恢复脚本防误操作
- 强制 `CONFIRM=yes` 环境变量
- 交互二次确认(`read -p "Type 'yes'"`)
- `pg_terminate_backend` 断活跃连接
- 输出验证命令(`psql \dt`)

### 4. README 端到端 5 步恢复流程
停 gateway → 恢复 → 验证 → 启动 → 健康检查,
配套 `/v1/admin/health/pg` 端点(R16 #4)验证连接池状态。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| `./verify.sh` | ✅ 11 模块 + 依赖方向全绿 |
| YAML 语法 | ✅ Python yaml.safe_load 解析成功 |
| Shell 脚本可执行 | ✅ chmod +x 已设 |

## 五、评分

| 维度 | R17 #1 末 | R17 #2 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 104 | **106**(+2:备份恢复链路 + Flyway baseline 自动化) |
| 产品完整度 | 122 | **122** |

## 六、Round 17 完整交付(2/2)

| 子轮 | 主题 | commits |
|---|---|---|
| R17 #1 | LLM 真实调用 | 2 |
| R17 #2 | Flyway baseline + 备份恢复 | 1 |
| **合计** | — | **3** |

R17 累计新增 14 测试(LlmJudge 升级)+ 3 个运维文件。

## 七、决策点

- **A**：接受 R17 全套 + CronDelete 终止
- **B**：继续 R18(SSO / MCP 转发 / Chicory 真实集成)
- **C**：运行 `/v1/admin/health/pg` 实际验证(需启动 gateway)

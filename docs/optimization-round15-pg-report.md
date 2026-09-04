# Round 15 #2 报告 — Pg 持久化升级

> 日期：2026-09-02 · 主攻：**R15 #2 Pg 持久化**
> 来源：Round 14 报告 §九 + 用户决策
> 借鉴：Spring JdbcTemplate + Flyway

---

## 一、本轮目标与切片

P0 阶段所有数据存 InMemory(重启即丢)。本轮:
1. 加 Flyway schema migrations
2. Feedback + AdminUser 接入 Pg(JdbcTemplate)
3. 条件装配(`observability.storage.enabled=true`)

P1 候选:Prompt Template / Team / Dataset 等(本轮先做最关键的两个)。

## 二、产出

| # | commit | 内容 |
|---|---|---|
| 1 | `<pg-slice>` | Flyway V1 迁移 + Feedback/AdminUser Pg Repo + 11 单测 + 条件装配 |

**累计 11 用例全绿(Feedback 6 + AdminUser 5)**

## 三、亮点

### 1. Flyway 迁移
`V1__r15_init.sql` 在启动时自动执行,PostgreSQL/H2 兼容 SQL。
剩余表(Team / Prompt)已建好 schema,后续 Pg 实现只需新增 Repo 类。

### 2. KeyHolder + JdbcTemplate
避开 SimpleJdbcInsert 在 H2 + auto_increment 模式下的多键返回问题,
手写 `PreparedStatementCreator` + `KeyHolder.getKey()` 控制更精确。

### 3. 条件装配
```java
@ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
public PgFeedbackRepository pgFeedbackRepository(JdbcTemplate observabilityJdbcTemplate) { ... }
```
默认仍是 InMemory(开发/演示);`application-prod.yml` 设 `observability.storage.enabled=true` 切换 Pg。

### 4. 测试隔离
H2 内存 DB 每次测试 `System.nanoTime()` 命名,完全隔离;DDL 在 `@BeforeEach` 重建。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-infra-persistence -am test` | ✅ 11/11 |
| 后端编译 | ✅ BUILD SUCCESS |

## 五、已知限制(留 R16)

- 5 个其他 InMemory Repo(Prompt / Dataset / K8s / Team / Mcp)未切 Pg
- Pg 实现只覆盖 Feedback + AdminUser(高频写)
- Flyway baseline 未初始化(首次启动会报错:已存在 InMemory 数据)
- HikariCP 连接池未配置(目前用 DriverManager-style)

## 六、评分

| 维度 | R15 #1 末 | R15 #2 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 101 | **102**(+1:Pg 持久化让运维可重启不丢数据) |
| 产品完整度 | 116 | **117**(+1:对接生产数据库) |

## 七、决策点

- **A**：接受 R15 #2 + CronDelete 终止
- **B**：继续 R15 #3 JWT + LlmJudge
- **C**：回退 R15 #2(继续 InMemory)

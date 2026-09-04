# Round 16 #1 报告 — Pg 覆盖扩展(Prompt + Team)

> 日期：2026-09-02 · 主攻：**R16 #1 Pg 覆盖剩余高频 Repo**
> 来源：Round 15 #2 报告 §五(已知限制)
> 借鉴：R15 #2 已建的 Flyway schema

---

## 一、本轮目标与切片

R15 #2 已建 Flyway V1 migration(7 表)+ Pg 实现 Feedback/AdminUser。
本轮扩展 Pg 覆盖到 **Prompt Template/Version** + **Team**(用户最常用的数据)。

R16 #2 继续覆盖 Dataset / K8s / Mcp 三个 Repo。

## 二、产出

| # | commit | 内容 |
|---|---|---|
| 1 | `<pg-prompt-team>` | PgPromptTemplateRepository + PgPromptVersionRepository + PgTeamRepository + 12 单测 |

**累计 12 用例全绿(Prompt 7 + Team 5)**

## 三、亮点

### 1. PromptVersion 参数 JSON 序列化
`params: Map<String, Object>` → JSON 字符串 → CLOB 存储;
反序列化用 Jackson ObjectMapper;测试覆盖了 `temperature=0.3` 复杂对象。

### 2. Team 多对多关联表
team + team_member 两张表;
`loadMembers()` 在静态 RowMapper 之外实例方法查询,
避免 static context 引用 instance field 的坑。

### 3. 同步策略:全删全插
Team 保存时 `DELETE FROM team_member WHERE team_id=?` 后逐条 INSERT;
P0 简化策略(并发场景需 R17 加乐观锁)。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-infra-persistence -am test` | ✅ 12/12 |

## 五、Pg 覆盖进度

| Repo | R15 #2 | R16 #1 | R16 #2 候选 |
|---|---|---|---|
| Feedback | ✅ | — | — |
| AdminUser | ✅ | — | — |
| Team | — | ✅ | — |
| PromptTemplate | — | ✅ | — |
| PromptVersion | — | ✅ | — |
| Dataset | — | — | ⏳ |
| K8sGateway | — | — | ⏳ |
| McpPort | — | — | ⏳ |

**5/8 已切 Pg**(62%);剩余 3 个 R16 #2 完成。

## 六、评分

| 维度 | R15 末 | R16 #1 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 103 | **104**(+1:Prompt 数据生产可重启) |
| 产品完整度 | 119 | **120**(+1:多 Admin + Prompt 完整生产路径) |

## 七、决策点

- **A**：接受 R16 #1 + 继续 R16 #2(Dataset/K8s/Mcp Pg)
- **B**：接受 R16 #1 + 跳 R16 #3 Chicory Wasm
- **C**：终止 R16(8 表已覆盖 5,够用)

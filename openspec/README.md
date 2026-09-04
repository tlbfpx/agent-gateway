# openspec/

本项目采用 OpenSpec 轻量结构管理规范与变更。配合 `AGENTS.md` 的四阶段工作流。

## 目录结构

```
openspec/
├── README.md            本文件
├── PROPOSAL.md          项目级提案（整体 what/why/范围/风险/成功标准/路线）
└── changes/             每个变更一个文件夹（change = 一个可独立测试的交付）
    └── <change-name>/
        ├── proposal.md    本变更的 what/why/范围/验收
        ├── design.md      本变更的技术决策（细节指向 plans/ 或 specs/）
        └── tasks.md       任务清单视图（详细 step 指向 plans/）
```

## 与现有文档的关系（轻量采纳，不重写）

| OpenSpec 角色 | 本项目承载文件 | 说明 |
|---|---|---|
| 项目 design | `docs/superpowers/specs/2026-08-12-agent-gateway-design.md` | 完整技术设计（1777 行，4 轮评审） |
| 项目 proposal | `openspec/PROPOSAL.md` | 动机/范围/风险/成功标准 |
| 变更 tasks（详细） | `docs/superpowers/plans/2026-08-12-*.md` | writing-plans 产出的可执行 step |
| 变更 tasks（索引） | `openspec/changes/<name>/tasks.md` | 任务清单视图，指向 plans/ |
| 变更 design | `openspec/changes/<name>/design.md` | 本变更特有决策 |

## 四件套 Gate（对齐 AGENTS.md）

一个 change 进入实现（阶段三）前，其文件夹下 `proposal.md` + `design.md` + `tasks.md` 必须齐全且通过评审。
**行为规格（specs/）暂渐进**：当前用设计文档承载，后续按能力拆分可测试条款。

## 当前 changes

> 全部 12 个 change 已完成并归档到 `openspec/changes/archive/`。当前 `openspec/changes/` 仅含 `archive/` 目录。

| Change | 完成日期 | 归档路径 |
|---|---|---|
| `add-foundation-skeleton` | 2026-08-13 | `archive/2026-08-13-add-foundation-skeleton/` |
| `add-a2a-and-discovery` | 2026-08-13 | `archive/2026-08-13-add-a2a-and-discovery/` |
| `add-multi-model` | 2026-08-13 | `archive/2026-08-13-add-multi-model/` |
| `add-session-store` | 2026-08-14 | `archive/2026-08-14-add-session-store/` |
| `add-admin-console` | 2026-08-14 | `archive/2026-08-14-add-admin-console/` |
| `add-auth-and-rbac` | 2026-08-14 | `archive/2026-08-14-add-auth-and-rbac/` |
| `add-cost-and-audit` | 2026-08-14 | `archive/2026-08-14-add-cost-and-audit/` |
| `add-observability` | 2026-08-14 | `archive/2026-08-14-add-observability/` |
| `add-openapi-and-example-agent` | 2026-08-14 | `archive/2026-08-14-add-openapi-and-example-agent/` |
| `add-orchestration-and-sse` | 2026-08-14 | `archive/2026-08-14-add-orchestration-and-sse/` |
| `redesign-admin-ui` | 2026-08-25 | `archive/2026-08-25-redesign-admin-ui/` |
| `complete-left-menu-coverage` | 2026-08-25 | `archive/2026-08-25-complete-left-menu-coverage/` |

后续计划路线见 `openspec/PROPOSAL.md`。

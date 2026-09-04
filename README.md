# Agent Gateway

公司级 Agent 通用网关：统一会话入口，SSE 流式对话，A2A 协议调用远程 Agent（Nacos 注册发现）。

技术栈：**Spring Boot 4.0 · Spring AI Alibaba 2.0.0-M1 · Nacos A2A · MiniMax/GLM/DeepSeek 多模型**

## 快速开始

    # 后端（8080）
    MINIMAX_API_KEY=sk-... mvn -pl gateway-bootstrap spring-boot:run
    # 前端（5173，代理 /v1 → 8080）
    cd agent-gateway-ui && npm install && npm run dev

首次使用：打开 http://localhost:5173 → 顶栏「⚙ 运营」→ 签发 API Key → 填入右栏 → 「模型管理」配置模型。

## 功能总览

**对话**（`/v1/chat`、`/v1/chat/stream`）
- SSE 流式逐字输出 · Markdown/代码块渲染 · 多轮上下文记忆（HistoryPolicy 可插拔：LastN/滚动摘要）
- 工具调用可视化（Agent 调用中/完成/失败）· 会话列表时间分组 · PII 实时脱敏（可开关）
- 消息级用量透明：每条回答显示实际命中模型（灰度分流后）+ token 估算（`done` 事件 `meta` 字段）

**模型接入**（可插拔 Provider SPI：minimax/deepseek/zhipuai/openai/兼容协议）
- 管理菜单动态配置（provider/key/厂商模型名），持久化 `data/models.json` 重启不丢
- 同名灰度组按 weight 加权分流 · 能力降级 failover（无 function-calling 时切 fallback）· 灰度分组效果对比报表（各成员请求数/延迟分位/错误率/成本，`/v1/admin/models/{id}/grayscale-comparison`）
- 版本历史 + 回滚 + 任意两版字段级 diff

**安全与治理**
- API Key 双通道（签发/吊销持久化 `data/api-keys.json`）· Key 过期时间（`expiresAt`，过期自动 401）· 模型白名单 · Agent 级 + **Skill 级 RBAC**
- 限流五维度（租户/用户/Key QPS + Agent 并发 + token 日预算）→ 429
- 运营台独立管理凭据 `X-Admin-Token`（`gateway.security.admin-token`，默认空=关闭，与用户 API Key 分离）
- 审计日志（认证/授权/限流/Agent 调用）append-only + 查询端点
- Webhook 事件推送（HMAC 签名 + 指数退避重试 + 死信队列）

**可观测**：Micrometer 指标（`/actuator/metrics`）· OpenAPI 3.0（`/v1/openapi.json`）· readiness/liveness 分离（`/v1/ready` `/v1/health`）

**预算治理**：预算 80%/100% 两级告警（AlertCenter + Webhook 推送，去重）· 超限动作可配 BLOCK（默认 429）/ DOWNGRADE（降级到 fallbackModel 继续服务）

**运营**：模型管理 · Webhook 订阅 · 审计查询 · 配置版本/回滚/diff（全部前端可视化）

## 运维

    ./verify.sh              # 一键门禁：编译+全模块测试+依赖方向断言
    mvn clean install      # 全量构建
    # 优雅停机 30s；data/ 目录为运行时数据（已 gitignore）

## 文档

> ⚠️ **生产部署前必读**：[`docs/known-limitations.md`](docs/known-limitations.md) —— 内存态存储范围 + 插件隔离边界 + 长期搁置项的当前状态

- **`docs/known-limitations.md`**（生产部署前必读）
- 设计 spec：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§1-29）
- 变更史：`openspec/changes/`（OpenSpec 四件套）
- 协同规范：`AGENTS.md`

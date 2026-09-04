# Proposal: 语义缓存（round8-semantic-cache）

> **状态**：实现已完成（commit `295bf08c`），本变更记录为事后补档
> **来源**：Round 8 候选 — 重复/相似 query 命中历史响应，降低 LLM 成本与延迟

## 动机

LLM 调用成本高、延迟大，而企业级场景中**大量 query 重复或相似**（如客服场景"如何退款？"、研发场景"如何重置密码？"）。当前架构每次都走完整 LLM 链路，无任何复用机制。

Round 7 引入 `promptCache`（精确匹配字符串规范化）作为第一步；本变更升级为**双层语义缓存**：
- **L1 精确匹配**：query 文本规范化（去空白、去时态、去 PII）后 hash，命中即返回（O(1)）
- **L2 向量召回**：L1 miss 时用 embedding 余弦相似度召回 top-K（O(log n)），threshold 由运营配置

## What

### 领域接口（gateway-domain/cache）
- `SemanticCachePort`：缓存查找/写入端口
- `CacheLookupResult`：lookup 结果 DTO（hit/miss/similarity/kind）
- `EmbeddingPort`：向量生成端口（依赖外部 embedding 服务，如 OpenAI text-embedding-3）
- `QueryNormalizer`：query 规范化（小写、去空白、trim、停用词、时态归一化）
- `PiiDetector`：PII 检测（regex + 关键词；命中即不写缓存，避免敏感 query 落盘）
- `SemanticCacheFacade`：门面服务（统一 L1+L2 + 写入策略）

### 基础设施
- `gateway-infra-cache`（新模块）：
  - `SemanticCacheService`：默认实现（ConcurrentHashMap L1 + 简易向量 L2）
  - `OpenAiEmbeddingClient`：OpenAI embedding 调用
  - `SemanticCacheProperties` / `AutoConfiguration`：条件装配
  - `SemanticCacheAdminController`：运营端点（命中率/清理/Top 命中）
- `gateway-infra-persistence/cache`：
  - `PgSemanticCacheRepository`：PG 持久化（生产路径）
  - `SemanticCacheSchemaInitializer` + `schema-semantic-cache.sql`

### 集成
- `ChatOrchestrator`：注入 `SemanticCacheFacade`
  - 查询路径：cache hit → 直接 emit Delta，跳过 LLM 调用，上报 `cache.hit` 事件
  - 写入路径：LLM 完成后异步写缓存（`costSavedCents` 由 quota 模块补充精确值）
  - 命中条件：**无工具 + 无多轮 history**（避免 tool_calls 上下文污染）

### UI
- `/cache` 路由（Sidebar "语义缓存" 入口）
- `pages/Cache.tsx`：命中率/节省成本/Top 命中 query 可视化
- `lib/api/cache.ts`：前端 SDK

## Non-goals

- 不做多租户隔离（Round 9 待办；当前 cache key 含 tenant_id 已做初步隔离）
- 不做 eviction 策略自动调优（运营手动配置 TTL + max-size）
- 不做 embedding 缓存（每次调用 OpenAI embedding API；缓存由 OpenAI 自身负责）
- 不动 promptCache（Round 7 已有的 L1 精确匹配保留作为降级路径）

## 验收

- 后端：domain/cache 测试 + infra-cache 测试 + persistence/cache 测试全过
- 集成：`ChatOrchestrator` 在 cache hit 时不调 LLM（mock 验证）
- UI：Cache 页面渲染命中率 + Top 命中 query 列表
- 配置：`gateway.semantic-cache.enabled=true`（默认 false，向后兼容）
- 测试覆盖：query 标准化、PII 阻断、命中阈值、写入异步、tenant 隔离
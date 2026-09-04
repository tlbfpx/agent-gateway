# Design: 语义缓存

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| 缓存分层 | L1 精确 + L2 向量召回 | L1 解决常见重复（O(1)），L2 解决语义相似（O(log n)）；两层独立可降级 |
| L1 key | SHA-256(normalize(query)) | 确定性、无碰撞、可调试 |
| L2 距离 | cosine 相似度 ≥ 0.92（可配） | 业界默认阈值；过低会假阳性，过高命中率低 |
| PII 阻断 | regex + 关键词正则；命中即 skip 写入 | 防止"用户邮箱/手机号"落盘 |
| 写入策略 | 异步 fire-and-forget（不阻塞响应） | LLM 响应已发出，写失败不应回滚 |
| 失效 | TTL（默认 7 天） + LRU max-size | 防止无限增长；运营可手动 purge |
| Embedding | OpenAI text-embedding-3-small | 性价比高；1536 维 |
| Tenant 隔离 | cache key 含 tenant_id | 防止跨租户命中 |

## 2. 数据流

```
ChatController → ChatOrchestrator.orchestrate()
  ↓
SemanticCacheFacade.lookup(tenant, model, query, toolsSig)
  ├─ L1: hash(normalize(query)) → CacheLookupResult.HIT(L1)
  └─ L2: OpenAI embed → pg vector cosine top-K → CacheLookupResult.HIT(L2) / MISS
  ↓
if HIT: emit Delta + events.publish("cache.hit", ...)
if MISS: 走原 LLM 链路
  ↓
LLM 完成 → async semanticCache.writeAsync(tenant, model, query, response, cost, toolsSig)
  ├─ PiiDetector.check(query) → false 时才写
  └─ L1 + L2 双写
```

## 3. 配置

```yaml
gateway:
  semantic-cache:
    enabled: ${SEMANTIC_CACHE_ENABLED:false}  # 默认关闭,显式开启
    l2-threshold: 0.92          # cosine 相似度阈值
    embedding-api-key: ${OPENAI_API_KEY:}
    ttl-days: 7
    max-size: 10000            # LRU 上限
```

## 4. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 假阳性（语义相似但语义不同） | threshold 0.92 + 运营可视化 Top 命中便于审核；可一键 disable |
| PII 漏检 | PII 检测覆盖邮箱/手机/身份证/银行卡；新增敏感词可热更新 |
| Embedding 成本 | L1 命中绕过 embedding；embedding 失败回退到 L1-only |
| 缓存穿透 | 同一 query 100 并发只查一次（in-flight map + CompletableFuture） |

## 5. 涉及文件

| 模块 | 文件 |
|---|---|
| gateway-domain/cache | CacheLookupResult / EmbeddingPort / PiiDetector / QueryNormalizer / SemanticCacheFacade / SemanticCachePort |
| gateway-domain/test/cache | 单元测试 |
| gateway-infra-cache（新模块） | SemanticCacheService / OpenAiEmbeddingClient / SemanticCacheProperties / SemanticCacheAutoConfiguration / SemanticCacheAdminController |
| gateway-infra-persistence/cache | PgSemanticCacheRepository / SemanticCacheSchemaInitializer / schema-semantic-cache.sql |
| gateway-application | ChatOrchestrator 集成 cache lookup + async write |
| agent-gateway-ui | pages/Cache.tsx / lib/api/cache.ts / Sidebar / Routes |
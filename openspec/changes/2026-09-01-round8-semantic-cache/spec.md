# Spec: 语义缓存（可测试条款）

#### GW-CACHE-001 配置开关
**MUST**：`gateway.semantic-cache.enabled=false`（默认）时，SemanticCacheFacade 不执行 lookup/write，ChatOrchestrator 行为与未启用完全一致。
**测试**：ChatOrchestratorTest.semanticCacheDisabled_bypassesLookup。

#### GW-CACHE-002 L1 精确匹配
**MUST**：query 经 QueryNormalizer 规范化后（去空白/小写/trim），相同文本 L1 命中（O(1)），返回原 response，无 embedding 调用。
**测试**：SemanticCacheServiceTest.l1Hit_skipsEmbedding / QueryNormalizerTest.normalizeStripsWhitespaceAndCase。

#### GW-CACHE-003 L2 向量召回
**MUST**：L1 miss 时调 EmbeddingPort 生成向量，与已存向量计算 cosine 相似度；top-1 ≥ `l2-threshold`（默认 0.92）即命中。
**测试**：SemanticCacheServiceTest.l2Hit_aboveThreshold / l2Miss_belowThreshold。

#### GW-CACHE-004 PII 阻断
**MUST**：PiiDetector 检测到邮箱/手机号/身份证/银行卡号的 query 跳过写入（lookup 仍可命中已有的非敏感缓存）。
**测试**：PiiDetectorTest.detectsEmailPhoneIdCard / SemanticCacheServiceTest.piiQuery_skipsWrite。

#### GW-CACHE-005 Tenant 隔离
**MUST**：cache key 必含 tenant_id；不同 tenant 的相同 query 不命中彼此。
**测试**：SemanticCacheServiceTest.tenantIsolation。

#### GW-CACHE-006 命中路径不调 LLM
**MUST**：cache hit 时 ChatOrchestrator 不调用 ChatClientPort，直接 emit Delta + 上报 `cache.hit` 事件（含 tenant/model/kind/similarity）。
**测试**：ChatOrchestratorTest.cacheHit_emitsDeltaWithoutLlmCall / cacheHit_publishesEvent。

#### GW-CACHE-007 异步写入不阻塞响应
**MUST**：LLM 完成后 writeAsync 在独立线程执行；write 失败仅日志，不影响响应发出。
**测试**：SemanticCacheServiceTest.asyncWrite_doesNotBlockResponse / writeFailure_loggedNotPropagated。

#### GW-CACHE-008 写入条件：无工具 + 无多轮 history
**MUST**：仅当 `tools.isEmpty() && historyEmpty` 时才尝试 lookup/write；有 tool_call 或多轮 history 时跳过（避免上下文污染）。
**测试**：ChatOrchestratorTest.cacheBypassed_whenTools / cacheBypassed_whenMultiTurn。

#### GW-CACHE-009 配置注入
**MUST**：`SemanticCacheProperties` 暴露 `enabled / l2-threshold / embedding-api-key / ttl-days / max-size`，与 `gateway.semantic-cache.*` 配置项绑定。
**测试**：SemanticCachePropertiesTest.bindsAllFields。

#### GW-CACHE-010 运营端点
**MUST**：`/v1/admin/cache/status` 返回命中率/Top 命中 query/Total saved cost；`/v1/admin/cache/purge` 清空全部；`/v1/admin/cache/purge?tenant=X` 按租户清空。
**测试**：SemanticCacheAdminControllerTest.statusReturnsMetrics / purgeAll / purgeByTenant。

#### GW-CACHE-011 Schema 幂等
**MUST**：`schema-semantic-cache.sql` 重复执行不报错（CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS）。
**测试**：PgSemanticCacheRepositoryTest.schemaIdempotent（IT profile）。
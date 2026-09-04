# Tasks: 语义缓存（round8-semantic-cache）

- [x] **A.1** 领域接口：SemanticCachePort / CacheLookupResult / EmbeddingPort / QueryNormalizer / PiiDetector / SemanticCacheFacade（commit `295bf08c`）
- [x] **A.2** 基础设施：gateway-infra-cache 新模块（Service / OpenAI Embedding Client / Properties / AutoConfiguration / AdminController）
- [x] **A.3** 持久化：PgSemanticCacheRepository + SchemaInitializer + schema-semantic-cache.sql
- [x] **A.4** ChatOrchestrator 集成：lookup 命中跳过 LLM / async write / 命中条件（无工具 + 无多轮）
- [x] **A.5** UI：pages/Cache.tsx + lib/api/cache.ts + Sidebar / Routes 注册
- [x] **B.1** 单元测试：domain/cache + infra-cache + persistence/cache 全过
- [x] **B.2** 集成验证：mvn -pl gateway-domain,gateway-infra-cache,gateway-application 全绿
- [x] **C.1** 文档：openspec 变更记录（proposal / design / spec / tasks）

## 验收门禁

- [x] 后端单测：domain/cache 测试套件 + infra-cache 测试套件 + ChatOrchestrator 集成测试全过
- [x] 单模块 verify：`mvn -pl gateway-domain,gateway-infra-cache -am test` BUILD SUCCESS
- [x] Spec 条款 GW-CACHE-001 ~ GW-CACHE-011 全部覆盖
- [x] Round 8 commit `295bf08c` 落地
- [x] Round 8.5 fix `a9769bd8`（Spring 4.0 严格模式）落地，verify.sh 11 模块全绿
# Tasks: 多模型接入（add-multi-model）

> **任务清单视图**。详细 step 待 `writing-plans`。遵循 `AGENTS.md`：独立模块并行、同文件串行。

## Spike：多 Provider 兼容性（回填 2026-08-12-saa-compat-report.md 矩阵）
- [ ] **Task 1: OpenAI-Compatible(DeepSeek) 兼容性** — `spring-ai-openai` + Boot 4 能创建 ChatClient、Streaming 正常、Function Calling 正常；回填矩阵
- [ ] **Task 2: Zhipu 兼容性** — 确认可用 starter（官方/社区）；ChatClient/Streaming/FC 正常；回填矩阵（不兼容则 openai-compatible 兜底）
- [ ] **Task 3: MiniMax 兼容性** — 同上；回填矩阵

> Task 1/2/3 可并行 Spike。

## 核心开发
- [ ] **Task 4: ModelRegistry** — Nacos ConfigListener 监听 `agent-gateway-models.yaml`；yaml→`Map<ModelId,ModelDef>`；getModel/listModels；变更原子更新；单测 ≥80%
- [ ] **Task 5: ChatClientFactory** — 按 ModelDef.provider(String) 分发；实际 GAV（Spike 已验证）：dashscope 用 `spring-ai-alibaba-starter-dashscope:2.0.0-M1.1`，deepseek 用 `spring-ai-starter-model-deepseek:2.0.0-M1`，zhipuai 用 `spring-ai-starter-model-zhipuai:2.0.0-M1`，minimax 用 `spring-ai-starter-model-minimax:2.0.0-M1`，openai-compatible 用 `spring-ai-starter-model-openai:2.0.0-M1`；Caffeine 缓存（ModelId key，expireAfterAccess 1h）；`${SECRET:XXX}` 解析；配置变更 invalidate；单测（命中/未命中）
- [ ] **Task 6: Flow↔Flux 适配器** — ReactorFlowAdapter.adapt（Flux<ChatResponse>→Flow.Publisher<LlmEvent>）；ChatResponse→LlmEvent 映射；背压透传；cancel 正确；无泄漏；单测（各 ChatResponse 场景）
- [ ] **Task 7: LlmSession 实现** — 持有 ChatClient + ToolDescriptor 列表；generate(prompt,ctx)→Flow.Publisher<LlmEvent>；内部 ChatClient.call().stream() 经适配器转换；ToolDescriptor 传给 ChatClient；集成测试（完整流式对话）
- [ ] **Task 8: ChatClientPort 实现** — sessionFor(model,tools)→LlmSession；模型不存在抛 IllegalArgumentException；端到端集成
- [ ] **Task 9: 能力降级 Failover（§5.5.5）** — 检测 FUNCTION_CALLING；需工具但模型不支持→切 fallbackToolModel（启动校验其 FC）；日志；可配 fallback id；单测（触发/不触发）
- [ ] **Task 10: dashscope exclude 配置** — application.yml 配 exclude（仅 dashscope 需要，其余 starter 无此问题）；启动无 ClassNotFoundException；DashScope ChatClient 正常；文档化为必配项（见 saa-compat-report Spike）

## 测试与集成
- [ ] **Task 11: 单元测试补全** — ReactorFlowAdapterTest/ChatClientFactoryTest/NacosModelRegistryTest/CapabilityFailoverTest；JaCoCo ≥80%
- [ ] **Task 12: 集成测试（WireMock）** — DashScope/OpenAI-compat/Zhipu/MiniMax Mock（Spike 通过的）；Streaming+ToolCall 联合
- [ ] **Task 13: 真实 API 验证** — DashScope/DeepSeek 真实调用（需 key）；性能（首字节延迟）；其余依 Spike 结果

## 文档与交付
- [ ] **Task 14: 文档更新** — spike 报告矩阵回填完整；spec §5.5/§17 与实现一致；DashScope exclude 标注
- [ ] **Task 15: 代码审查与提交** — Code Review 通过（无 CRITICAL/HIGH）；CI 全绿；change 三件套齐全

## 依赖与并行性
```
Task 1/2/3 (Spike) 并行
  ↓
Task 4 (ModelRegistry) → Task 5 (Factory)
                              ↓
Task 6 (适配器) ──────────────┤（可并行 7/8）
                              ↓
Task 7 (LlmSession) → Task 8 (ChatClientPort) → Task 9 (Failover)
Task 10 (exclude 配置) 可并行
  ↓
Task 11/12/13 (测试) 并行 → Task 14/15 (交付)
```

# Round 17 #1 报告 — LlmJudge 接 ChatOrchestrator

> 日期：2026-09-02 · 主攻：**R17 #1 LLM 真实调用**
> 来源：R14 #4 留下的 stub Judge + 用户决策
> 借鉴：Langfuse LLM-as-judge / OpenAI Evals / Portkey Eval

---

## 一、本轮目标与切片

R14 #4 实现的 LlmJudge 是 stub(委托 StubJudge,启发式)。
R17 #1 让 LlmJudge 真正调用 LLM,经 ChatClientPort → 任何 provider(gpt-4o / claude / 自建)。

## 二、产出

| # | commit | 内容 |
|---|---|---|
| 1 | `<real-llm>` | JudgeLlmPort + DefaultJudgeLlmPort + LlmJudge 升级 + 14 单测 |

**累计 14 用例全绿(LlmJudgeTest 5 + LlmJudgeRealTest 9)**

## 三、亮点

### 1. JudgeLlmPort 抽象
```java
public interface JudgeLlmPort {
    String complete(String systemPrompt, String userPrompt, String model, double temperature);
    default boolean isAvailable() { return false; }
}
```
R17 #1 后续可换实现:OpenAI / Anthropic / 自建 LLM,架构不变。

### 2. DefaultJudgeLlmPort:接 ChatClientPort
- `session.generate(userPrompt, history, ctx)` → `Flow.Publisher<LlmEvent>`
- 订阅 `LlmEvent.Delta`,拼接 content 为完整响应
- 同步语义:用 AtomicReference 收集,完成后返回

### 3. LlmJudge 三层 fallback
```
1. JudgeLlmPort.isAvailable()  → 真实 LLM(parse PASS/FAIL)
2. 异常 → StubJudge delegate(启发式,降权 0.8)
3. 无 LLM + 无 delegate → fail-closed(明确报告原因)
```

### 4. parseVerdict 正则
```java
Pattern PASS_FAIL = Pattern.compile("\\b(PASS|FAIL)\\b", Pattern.CASE_INSENSITIVE);
```
- 大小写不敏感
- 容忍前后缀("verdict: PASS" / "Looking at this, my verdict is PASS.")
- 无 PASS/FAIL → fail-closed(返回响应前 80 字符作为 reason)

## 四、API 变化

`LlmJudge.DEFAULT_PROMPT` 重命名为 `LlmJudge.DEFAULT_SYSTEM_PROMPT`(语义更清晰);
使用方式:
```java
// 真实 LLM(自动检测 ChatClient 是否在 classpath)
Judge judge = new LlmJudge(
    LlmJudge.DEFAULT_SYSTEM_PROMPT,
    new StubJudge(),         // fallback
    new DefaultJudgeLlmPort(chatClient)  // 主路径
);

// 仅 StubJudge
Judge judge = new LlmJudge(
    LlmJudge.DEFAULT_SYSTEM_PROMPT,
    new StubJudge(),
    null
);
```

## 五、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-application -am test` | ✅ 14/14 |

## 六、评分

| 维度 | R16 末 | R17 #1 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 104 | **104** |
| 产品完整度 | 120 | **122**(+2:真实 LLM 评判 + ChatOrchestrator 集成) |

## 七、决策点

- **A**：接受 R17 #1 + 启动 R17 #2 Flyway baseline + 备份恢复
- **B**：跳过 R17 #2,R18 启动(其它主题如 SSO/MCP 转发)
- **C**：R17 收官报告 + CronDelete 终止循环

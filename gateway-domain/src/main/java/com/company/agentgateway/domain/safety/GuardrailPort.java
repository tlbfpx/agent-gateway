package com.company.agentgateway.domain.safety;

import java.util.List;

/**
 * Guardrail 端口(Round 9):domain 抽象接口。
 *
 * <p>三个检查点(均由 infra 实现,domain 仅声明):
 * <ul>
 *   <li>{@link #checkInput} — 调 LLM 前(query)</li>
 *   <li>{@link #checkOutput} — LLM 返回后(response)</li>
 *   <li>{@link #checkToolCall} — tool_call 前(name + args)</li>
 * </ul>
 */
public interface GuardrailPort {

    /**
      检查输入 query。
      @return 违规列表(空 = 放行);根据 policy.mode 由 caller 决定 block / observe / redact
     */
    List<GuardrailViolation> checkInput(String query, String tenant, String traceId);

    /**
      检查输出 response。
      @return 违规列表;REDACT 模式下 caller 应调 PiiRedactor.redact(response)
     */
    List<GuardrailViolation> checkOutput(String response, String tenant, String traceId);

    /**
      检查 tool_call。
      @return 违规列表;BLOCK 模式下 caller 应拒绝调用
     */
    List<GuardrailViolation> checkToolCall(String toolName, String argsJson, String tenant, String traceId);
}
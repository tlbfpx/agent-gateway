package com.company.agentgateway.domain.workflow;

import java.util.List;
import java.util.Map;

/**
 * Switch 节点执行结果(spec C3):选中的 case 单步 StepRun + matched case 标识。
 * 失败时 firstError 不为空。
 */
public record SwitchResult(
        StepRun stepRun,
        String matchedCase,         // "case:value" 或 "default"
        List<StepRun> candidates,   // 全部 case 顺序(便于调试)
        Map<String, Object> outputs,
        String firstError) {
}
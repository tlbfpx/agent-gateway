package com.company.agentgateway.domain.workflow;

/**
 * Parallel join 模式(spec C2 §3.1):决定 parallel 节点如何合并分支结果。
 * <ul>
 *   <li>ALL:任一分支失败 → 整体 FAILED(本期 C2.1 默认,严格语义)</li>
 *   <li>ANY:任一分支成功 → 整体 COMPLETED(C2.2 实现,容错优先)</li>
 * </ul>
 * MAJORITY(超半数成功)留待 C2.2。
 */
public enum Join {
    ALL, ANY;

    public static Join of(String s) {
        if (s == null) return ALL;
        return switch (s.toUpperCase()) {
            case "ANY" -> ANY;
            default -> ALL;
        };
    }
}
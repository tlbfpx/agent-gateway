package com.company.agentgateway.domain.workflow;

import java.util.List;

/**
 * Parallel 节点定义(spec C2 §3.1):workflow 内多源 fan-out;join 模式决定结果合并语义。
 * branches 内每个 BranchDef.name 唯一(同 workflow 整体唯一,因为 parallel 与 chain name 空间合并)。
 */
public record ParallelDef(
        String name,
        Join join,
        List<BranchDef> branches) {
}
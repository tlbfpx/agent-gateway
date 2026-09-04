package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;

import java.util.List;

/**
 * 路由指标端口(Round 10):domain 层抽象接口;infra 层(Micrometer / Caffeine)实现。
 *
 * <p>由 AutoRouter 在决策前查询候选模型的 5min 滑动窗口指标快照。
 * 实现可以是 Micrometer(MeterRegistry 聚合)、Caffeine 内存窗口、或未来 PG 时序存储。
 */
public interface RoutingMetricsPort {

    /**
     * 查询指定模型集合的当前指标快照;返回顺序与 input 一一对应(无指标 = empty snapshot)。
     *
     * @param modelIds 待查询的模型 ID
     * @return 每个模型的指标快照
     */
    List<RoutingMetricsSnapshot> snapshot(List<ModelId> modelIds);

    /** 单模型查询便捷方法。 */
    default RoutingMetricsSnapshot snapshotOne(ModelId modelId) {
        return snapshot(List.of(modelId)).stream().findFirst()
                .orElseGet(() -> RoutingMetricsSnapshot.empty(modelId));
    }
}
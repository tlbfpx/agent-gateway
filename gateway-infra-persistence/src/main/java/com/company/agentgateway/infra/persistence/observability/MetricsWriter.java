package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.MetricPoint;

import java.util.List;

/**
 * 指标写入端口(供 observability 模块的 publisher 回调,避免其直接依赖 JDBC)。
 */
public interface MetricsWriter {
    int batchInsert(List<MetricPoint> points);
}

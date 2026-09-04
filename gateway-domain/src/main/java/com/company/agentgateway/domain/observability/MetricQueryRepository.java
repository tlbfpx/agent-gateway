package com.company.agentgateway.domain.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 出站端口:指标时序查询(spec 2026-08-19 §4.2/§5.4)。由 infra-persistence 实现(PgMetricsStore)。
 */
public interface MetricQueryRepository {

    /** 区间序列查询(趋势图数据源):按 tags 精确匹配,时间升序。 */
    List<MetricPoint> querySeries(String metricName, Map<String, String> tags, Instant from, Instant to);

    /** 窗口聚合值(告警引擎求值用):窗口内 sum。 */
    OptionalDouble windowSum(String metricName, Map<String, String> tags, Instant from, Instant to);

    /**
     * 分桶聚合序列(Dashboard 趋势图数据源):按 bucketSeconds 分桶 sum,时间升序。
     * 桶内无数据的时段不返回(前端补零)。
     */
    List<MetricBucket> queryBuckets(String metricName, Map<String, String> tags,
                                    Instant from, Instant to, int bucketSeconds);

    /** 分桶聚合点。 */
    record MetricBucket(Instant bucketStart, double sum) {}
}

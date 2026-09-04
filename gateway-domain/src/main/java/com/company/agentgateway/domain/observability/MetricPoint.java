package com.company.agentgateway.domain.observability;

import java.time.Instant;
import java.util.Map;

/**
 * 指标采样点(spec 2026-08-19 §4.2)。value 为增量(delta)语义:
 * Counter/Timer 落库前已在快照侧差分,Gauge 存瞬时值。
 */
public record MetricPoint(String metricName, Map<String, String> tags, Instant ts, double value) {}

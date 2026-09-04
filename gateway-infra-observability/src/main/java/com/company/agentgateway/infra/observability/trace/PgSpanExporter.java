package com.company.agentgateway.infra.observability.trace;

import com.company.agentgateway.domain.observability.SpanRecord;
import com.company.agentgateway.infra.persistence.observability.SpanWriter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自研 SpanExporter:OTel span → SpanRecord → PG spans 表(spec 2026-08-19 §5.2)。
 *
 * <p>批量缓冲:队列 + 定时 flush(200 条或 5 秒,先到先触发)。
 * 失败仅日志告警,绝不阻断请求路径(§7 观测不拖垮主链路)。
 * 未来接 Tempo/Jaeger:并行追加 OTLPExporter,埋点零改动。
 */
public class PgSpanExporter implements SpanExporter {

    private static final Logger log = Logger.getLogger(PgSpanExporter.class.getName());

    private final SpanWriter spanWriter;
    private final BlockingQueue<SpanRecord> queue;
    private final int batchSize;
    private final ScheduledExecutorService scheduler;

    private volatile boolean degraded = false;

    public PgSpanExporter(SpanWriter spanWriter, int queueCapacity, int batchSize, int flushIntervalSeconds) {
        this.spanWriter = spanWriter;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pg-span-exporter");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::flushBatch,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public CompletableResultCode export(java.util.Collection<SpanData> spans) {
        for (SpanData data : spans) {
            // 队列满 → 丢弃(观测数据可丢,主链路不可断)
            if (!queue.offer(toRecord(data))) {
                degraded = true;
            }
        }
        if (queue.size() >= batchSize) {
            scheduler.execute(this::flushBatch);
        }
        return CompletableResultCode.ofSuccess();
    }

    /** OTel SpanData → 领域 SpanRecord(attributes 全部字符串化)。 */
    static SpanRecord toRecord(SpanData data) {
        Map<String, String> attributes = toStringMap(data.getAttributes());

        List<SpanRecord.SpanEvent> events = data.getEvents().stream()
                .map(e -> new SpanRecord.SpanEvent(
                        toInstant(e.getEpochNanos()),
                        e.getName(),
                        toStringMap(e.getAttributes())))
                .toList();

        long durationNanos = data.getEndEpochNanos() - data.getStartEpochNanos();

        return new SpanRecord(
                data.getTraceId(),
                data.getSpanId(),
                data.getParentSpanContext().isValid() ? data.getParentSpanContext().getSpanId() : null,
                data.getName(),
                SpanRecord.Kind.valueOf(data.getKind().name()),
                toInstant(data.getStartEpochNanos()),
                toInstant(data.getEndEpochNanos()),
                durationNanos / 1_000_000.0,
                data.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR
                        ? SpanRecord.Status.ERROR : SpanRecord.Status.OK,
                attributes, events);
    }

    private static Instant toInstant(long epochNanos) {
        return Instant.ofEpochSecond(epochNanos / 1_000_000_000L, epochNanos % 1_000_000_000L);
    }

    private static Map<String, String> toStringMap(Attributes attrs) {
        Map<String, String> out = new HashMap<>();
        attrs.forEach((k, v) -> out.put(k.getKey(), String.valueOf(v)));
        return out;
    }

    /** 批量落库;失败日志 + degraded 标记,数据丢弃防重试风暴(§7)。 */
    void flushBatch() {
        List<SpanRecord> batch = new ArrayList<>(Math.min(queue.size(), batchSize * 2));
        queue.drainTo(batch, batchSize * 2);
        if (batch.isEmpty()) return;
        try {
            spanWriter.batchInsert(batch);
            degraded = false;
        } catch (Exception e) {
            log.log(Level.WARNING, "span 落库失败(丢弃 {0} 条): {1}",
                    new Object[]{batch.size(), e.getMessage()});
            degraded = true;
        }
    }

    /** 存储是否处于降级态(health 端点暴露用)。 */
    public boolean isDegraded() {
        return degraded;
    }

    @Override
    public CompletableResultCode flush() {
        while (!queue.isEmpty()) {
            int before = queue.size();
            flushBatch();
            if (queue.size() >= before) break;  // 持续失败防死循环
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        flush();
        scheduler.shutdown();
        return CompletableResultCode.ofSuccess();
    }
}

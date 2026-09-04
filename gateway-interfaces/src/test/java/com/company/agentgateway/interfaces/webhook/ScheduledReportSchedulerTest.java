package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.InMemoryBillingRepository;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时报表订阅调度器测试（spec §25.4）。
 *
 * <p>用 stub WebhookDispatcher 捕获 publish 调用，避免真实 HTTP。
 */
class ScheduledReportSchedulerTest {

    /** 捕获 publish 的 stub；可配置抛异常模拟投递失败。 */
    static class CapturingDispatcher extends WebhookDispatcher {
        final List<Map<String, Object>> published = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        boolean throwOnPublish;

        @Override
        public void publish(String eventType, Map<String, Object> payload) {
            if (throwOnPublish) throw new IllegalStateException("dispatch boom");
            events.add(eventType);
            published.add(payload);
        }
    }

    private CapturingDispatcher dispatcher;
    private ScheduledReportRepository repository;
    private BillingPort billingPort;
    private ScheduledReportScheduler scheduler;

    @BeforeEach
    void setUp() {
        dispatcher = new CapturingDispatcher();
        repository = new ScheduledReportRepository();
        billingPort = new InMemoryBillingRepository();
        billingPort.recordUsage(sampleRecord("r1", "gpt-4o"));
        billingPort.recordUsage(sampleRecord("r2", "claude-3"));
        scheduler = new ScheduledReportScheduler(dispatcher, repository,
                new ReportFormatter(billingPort));
    }

    private static UsageRecord sampleRecord(String id, String model) {
        return new UsageRecord(id, new TenantId("primary"), new UserId("u1"),
                new ModelId(model), "agent-a", Instant.now().minusSeconds(60),
                100, 50, new BigDecimal("1.50"), new BigDecimal("0.01"), new BigDecimal("0.02"));
    }

    private ScheduledReport subscribe(String period, Instant nextFireAt) {
        return repository.save(new ScheduledReport(
                "rep-" + period + "-" + nextFireAt.toEpochMilli(), period, "24h", "model",
                "http://hook", "primary", Instant.now(), nextFireAt));
    }

    // ---- 1) nextFireAt <= now 触发并推进 nextFireAt ----

    @Test
    void 到期订阅触发投递并推进下次触发时间() {
        Instant due = Instant.now().minusSeconds(10);
        ScheduledReport report = subscribe("daily", due);

        scheduler.tick();

        assertThat(dispatcher.events).containsExactly("cost.report.daily");
        ScheduledReport after = repository.findById(report.reportId()).orElseThrow();
        assertThat(after.nextFireAt()).isEqualTo(due.plus(Duration.ofDays(1)));
        assertThat(after.nextFireAt()).isAfter(Instant.now());
    }

    // ---- 2) daily/weekly/monthly 三种 period 推进正确 ----

    @Test
    void 三种周期推进步长正确() {
        Instant due = Instant.now().minusSeconds(10);
        var daily = subscribe("daily", due);
        var weekly = subscribe("weekly", due);
        var monthly = subscribe("monthly", due);

        scheduler.tick();

        assertThat(repository.findById(daily.reportId()).orElseThrow().nextFireAt())
                .isEqualTo(due.plus(Duration.ofDays(1)));
        assertThat(repository.findById(weekly.reportId()).orElseThrow().nextFireAt())
                .isEqualTo(due.plus(Duration.ofDays(7)));
        assertThat(repository.findById(monthly.reportId()).orElseThrow().nextFireAt())
                .isEqualTo(due.plus(Duration.ofDays(30)));
        assertThat(dispatcher.events).containsExactlyInAnyOrder(
                "cost.report.daily", "cost.report.weekly", "cost.report.monthly");
    }

    // ---- 3) 投递失败不影响主流程（只 log warn） ----

    @Test
    void 投递抛异常不中断调度且其他订阅继续() {
        Instant due = Instant.now().minusSeconds(10);
        var a = subscribe("daily", due);
        var b = subscribe("weekly", due);
        dispatcher.throwOnPublish = true;

        scheduler.tick(); // 不抛出

        // 失败也推进 nextFireAt，避免下一 tick 立即重复轰炸
        assertThat(repository.findById(a.reportId()).orElseThrow().nextFireAt()).isAfter(due);
        assertThat(repository.findById(b.reportId()).orElseThrow().nextFireAt()).isAfter(due);
    }

    // ---- 4) 未到期订阅不触发 ----

    @Test
    void 未到期订阅不触发() {
        Instant future = Instant.now().plusSeconds(3600);
        ScheduledReport report = subscribe("daily", future);

        scheduler.tick();

        assertThat(dispatcher.events).isEmpty();
        assertThat(repository.findById(report.reportId()).orElseThrow().nextFireAt())
                .isEqualTo(future);
    }

    // ---- 5) 手动 test 跳过 nextFireAt 检查 ----

    @Test
    void 手动触发跳过到期检查() {
        Instant future = Instant.now().plusSeconds(3600);
        ScheduledReport report = subscribe("daily", future);

        boolean fired = scheduler.fireNow(report.reportId());

        assertThat(fired).isTrue();
        assertThat(dispatcher.events).containsExactly("cost.report.daily");
        // 手动触发不改变排期
        assertThat(repository.findById(report.reportId()).orElseThrow().nextFireAt())
                .isEqualTo(future);
    }

    @Test
    void 手动触发不存在的订阅返回false() {
        assertThat(scheduler.fireNow("no-such-id")).isFalse();
        assertThat(dispatcher.events).isEmpty();
    }

    // ---- 6) Repository delete 后不触发 ----

    @Test
    void 删除订阅后不再触发() {
        Instant due = Instant.now().minusSeconds(10);
        ScheduledReport report = subscribe("daily", due);
        assertThat(repository.delete(report.reportId())).isTrue();

        scheduler.tick();

        assertThat(dispatcher.events).isEmpty();
        assertThat(repository.findById(report.reportId())).isEmpty();
        assertThat(repository.delete(report.reportId())).isFalse();
    }

    // ---- 7) payload CSV 表头与 contentType 一致 ----

    @Test
    void 负载包含csv表头与contentType() {
        subscribe("daily", Instant.now().minusSeconds(10));

        scheduler.tick();

        Map<String, Object> payload = dispatcher.published.get(0);
        assertThat(payload).containsEntry("contentType", "text/csv");
        assertThat(payload).containsKeys("reportId", "period", "range", "dim", "tenant",
                "rows", "csv", "generatedAt");
        String csv = (String) payload.get("csv");
        assertThat(csv.lines().findFirst().orElseThrow())
                .isEqualTo(ReportFormatter.CSV_HEADER);
        assertThat(payload.get("rows")).isEqualTo(2);
        // 两条 usage 记录 → 表头 + 2 行
        assertThat(csv.lines().count()).isEqualTo(3);
        assertThat(csv).contains("gpt-4o").contains("claude-3");
    }

    // ---- ReportFormatter / Repository 单元覆盖 ----

    @Test
    void 格式化器空结果只返回表头且行数为零() {
        var formatter = new ReportFormatter(billingPort);
        var payload = formatter.buildPayload(new ScheduledReport("r", "daily", "24h", "model",
                "http://h", "empty-tenant", Instant.now(), Instant.now()));
        assertThat(payload.get("rows")).isEqualTo(0);
        assertThat((String) payload.get("csv")).isEqualTo(ReportFormatter.CSV_HEADER);
    }

    @Test
    void 格式化器按range换算查询窗口() {
        var captured = new UsageQuery[1];
        BillingPort spy = new InMemoryBillingRepository() {
            @Override
            public List<UsageRecord> exportUsage(UsageQuery query, ExportFormat format) {
                captured[0] = query;
                return List.of();
            }
        };
        var formatter = new ReportFormatter(spy);
        formatter.buildPayload(new ScheduledReport("r", "weekly", "7d", "model",
                "http://h", "primary", Instant.now(), Instant.now()));

        Duration window = Duration.between(captured[0].from(), captured[0].to());
        assertThat(window.toDays()).isEqualTo(7);
        assertThat(captured[0].tenant()).isEqualTo(new TenantId("primary"));
    }

    @Test
    void 仓储按租户筛选并分页() {
        for (int i = 0; i < 5; i++) {
            repository.save(new ScheduledReport("r" + i, "daily", "24h", "model",
                    "http://h", i < 3 ? "t1" : "t2", Instant.now(), Instant.now()));
        }
        assertThat(repository.list("t1", 0, 10)).hasSize(3);
        assertThat(repository.list("t2", 0, 10)).hasSize(2);
        assertThat(repository.list(null, 0, 10)).hasSize(5);
        assertThat(repository.list(null, 0, 2)).hasSize(2);
        assertThat(repository.list(null, 4, 10)).hasSize(1);
        assertThat(repository.list(null, 99, 10)).isEmpty();
    }

    @Test
    void 非法周期被拒绝() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ScheduledReport("r", "hourly", "24h", "model", "http://h",
                                "t", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ScheduledReport("r", "daily", "24h", "model", "  ",
                                "t", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

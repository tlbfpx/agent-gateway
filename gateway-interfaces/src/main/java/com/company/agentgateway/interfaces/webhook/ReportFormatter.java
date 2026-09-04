package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.TenantId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报表序列化：{@link UsageRecord} 列表 → CSV 字符串 + Webhook 负载（spec §25.4）。
 *
 * <p>数据获取复用 {@link BillingPort#exportUsage} 现有导出逻辑（domain 层
 * InMemoryBillingRepository 已实现租户隔离 + 时间窗过滤），避免在 application/interfaces
 * 层重复实现查询。本类只负责「取回的记录 → CSV 文本」这一步。
 */
public class ReportFormatter {

    /** CSV 表头（与 Chargeback 导出字段对齐，spec §21.5）。 */
    public static final String CSV_HEADER =
            "recordId,tenant,user,model,agent,timestamp,tokensIn,tokensOut,cost,unitPriceIn,unitPriceOut";

    /** 负载 contentType（订阅方据此判断解析方式）。 */
    public static final String CONTENT_TYPE = "text/csv";

    private final BillingPort billingPort;

    public ReportFormatter(BillingPort billingPort) {
        this.billingPort = billingPort;
    }

    /** 构造 Webhook 负载：查询窗口内的用量 → CSV + 元数据。 */
    public Map<String, Object> buildPayload(ScheduledReport report) {
        Instant to = Instant.now();
        Instant from = to.minus(report.rangeWindow());
        List<UsageRecord> records = billingPort.exportUsage(
                new UsageQuery(new TenantId(report.tenant()), from, to, null, null),
                ExportFormat.CSV);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.reportId());
        payload.put("period", report.period());
        payload.put("range", report.range());
        payload.put("dim", report.dim());
        payload.put("tenant", report.tenant());
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        payload.put("generatedAt", to.toString());
        payload.put("rows", records.size());
        payload.put("contentType", CONTENT_TYPE);
        payload.put("csv", toCsv(records));
        return payload;
    }

    /** 序列化为 CSV（空结果只返回表头）。 */
    public static String toCsv(List<UsageRecord> records) {
        if (records.isEmpty()) return CSV_HEADER;
        return records.stream()
                .map(ReportFormatter::toRow)
                .collect(Collectors.joining("\n", CSV_HEADER + "\n", ""));
    }

    private static String toRow(UsageRecord r) {
        return String.join(",",
                escape(r.recordId()),
                escape(r.tenant().value()),
                escape(r.user().value()),
                escape(r.model().value()),
                escape(r.agentName()),
                r.timestamp().toString(),
                String.valueOf(r.tokensIn()),
                String.valueOf(r.tokensOut()),
                r.cost().toPlainString(),
                r.unitPriceIn().toPlainString(),
                r.unitPriceOut().toPlainString());
    }

    /** CSV 转义：含逗号/引号/换行的字段加双引号，内部引号翻倍。 */
    private static String escape(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}

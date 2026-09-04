package com.company.agentgateway.interfaces.export;

/**
 * 导出格式(Round 9):
 * - XLSX:Excel,适合运营/合规查看
 * - PARQUET:大数据分析(留 P3 后续,基础设施 + 库较大)
 */
public enum ExportFormat {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");

    public final String wireName;
    public final String contentType;
    public final String extension;

    ExportFormat(String wireName, String contentType, String extension) {
        this.wireName = wireName;
        this.contentType = contentType;
        this.extension = extension;
    }

    /** 从 query 参数解析,缺失或非法 → 默认 XLSX;非法值抛 IllegalArgumentException 由 caller 返回 400。 */
    public static ExportFormat parse(String s) {
        if (s == null || s.isBlank()) return XLSX;
        return switch (s.toLowerCase()) {
            case "xlsx" -> XLSX;
            case "parquet" -> throw new IllegalArgumentException("parquet 导出留 Round 9 P3 后续");
            default -> throw new IllegalArgumentException("unsupported format: " + s);
        };
    }
}
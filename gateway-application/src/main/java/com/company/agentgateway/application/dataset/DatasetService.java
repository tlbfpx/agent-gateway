package com.company.agentgateway.application.dataset;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.dataset.EvalCase;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.infra.persistence.dataset.InMemoryDatasetRepositories;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据集用例层（spec 2026-09-02 §dataset-eval §5.1）。
 *
 * <p>支持 JSONL 上传解析（每行一条 JSON: {@code {"input":"...","expected":"...","weight":1,"metadata":{}}}）。
 */
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final InMemoryDatasetRepositories store;

    public DatasetService(InMemoryDatasetRepositories store) {
        this.store = store;
    }

    public EvalDataset create(String name, String description, String tenantId,
                              long ownerId, List<String> tags, AdminRole callerRole) {
        requireOperator(callerRole);
        EvalDataset d = store.saveDataset(EvalDataset.create(name, description, tenantId, ownerId, tags));
        log.info("dataset.created id={} name={} owner={}", d.id(), name, ownerId);
        return d;
    }

    /** JSONL 批量导入 cases;每行 1 个 JSON 对象。 */
    public int importJsonl(long datasetId, String jsonlContent, AdminRole callerRole) {
        requireOperator(callerRole);
        if (jsonlContent == null || jsonlContent.isBlank()) {
            throw new IllegalArgumentException("empty JSONL");
        }
        if (store.findDatasetById(datasetId).isEmpty()) {
            throw new IllegalArgumentException("dataset not found: " + datasetId);
        }
        String[] lines = jsonlContent.split("\\r?\\n");
        int imported = 0;
        int skipped = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            try {
                EvalCase c = parseCaseLine(datasetId, trimmed);
                store.saveCase(c);
                imported++;
            } catch (Exception ex) {
                skipped++;
                log.warn("dataset.import.skip datasetId={} line={}: {}", datasetId, line, ex.getMessage());
            }
        }
        log.info("dataset.import id={} imported={} skipped={}", datasetId, imported, skipped);
        return imported;
    }

    /** 极简 JSON 解析（避免引入 JSON 依赖;支持 {"input":"...","expected":"..."}） */
    @SuppressWarnings("unchecked")
    private EvalCase parseCaseLine(long datasetId, String line) {
        // 找 input / expected / weight / metadata 字段
        String input = extractString(line, "input");
        String expected = extractString(line, "expected");
        Integer weight = extractInt(line, "weight");
        Map<String, Object> metadata = extractObject(line, "metadata");
        // 校验:必须至少含 input 或 expected 之一,且整行必须是 JSON 对象
        if (input == null && expected == null) {
            throw new IllegalArgumentException("line missing input/expected");
        }
        if (!line.trim().startsWith("{")) {
            throw new IllegalArgumentException("line not a JSON object");
        }
        return EvalCase.create(datasetId, input == null ? "" : input,
                expected == null ? "" : expected,
                metadata == null ? Map.of() : metadata,
                weight == null ? 1 : weight);
    }

    private static String extractString(String json, String key) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Integer extractInt(String json, String key) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return null;
        int end = colon + 1;
        while (end < json.length() && (Character.isWhitespace(json.charAt(end)) || json.charAt(end) == '-')) end++;
        int start = end;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        if (start == end) return null;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractObject(String json, String key) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return null;
        int brace = json.indexOf('{', colon);
        if (brace < 0) return null;
        // 找到匹配的 }
        int depth = 0;
        int end = -1;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end < 0) return null;
        // 简化:返回 raw 字符串包一层(避免递归解析)
        return Map.of("raw", json.substring(brace, end + 1));
    }

    public List<EvalCase> findCases(long datasetId) {
        return store.findCasesByDataset(datasetId);
    }

    public int countCases(long datasetId) {
        return store.countCasesByDataset(datasetId);
    }

    public EvalDataset findById(long id) {
        return store.findDatasetById(id)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found: " + id));
    }

    public List<EvalDataset> findByTenant(String tenantId) {
        return store.findDatasetsByTenant(tenantId);
    }

    public boolean delete(long id, AdminRole callerRole) {
        requireAdmin(callerRole);
        boolean ok = store.deleteDataset(id);
        log.info("dataset.deleted id={} by={}", id, callerRole);
        return ok;
    }

    private static void requireOperator(AdminRole caller) {
        if (caller == null || !caller.atLeast(AdminRole.OPERATOR)) {
            throw new SecurityException("caller role " + caller + " insufficient (need OPERATOR)");
        }
    }

    private static void requireAdmin(AdminRole caller) {
        if (caller == null || !caller.atLeast(AdminRole.ADMIN)) {
            throw new SecurityException("caller role " + caller + " insufficient (need ADMIN)");
        }
    }
}

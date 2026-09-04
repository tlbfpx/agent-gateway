package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.dataset.DatasetService;
import com.company.agentgateway.application.dataset.EvalRunService;
import com.company.agentgateway.domain.dataset.EvalCase;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.domain.dataset.EvalRun;
import com.company.agentgateway.domain.dataset.EvalStrategy;
import com.company.agentgateway.domain.iam.admin.AdminRole;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集 + 评测端点（spec 2026-09-02 §dataset-eval §6）。
 *
 * <ul>
 *   <li>Dataset: POST + GET list + GET by id + DELETE</li>
 *   <li>Cases: POST /{id}/cases (JSONL) + GET /{id}/cases</li>
 *   <li>Run: POST /{id}/runs + GET /runs/{runId}</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/datasets")
public class AdminDatasetController {

    private final DatasetService datasetService;
    private final EvalRunService evalRunService;

    public AdminDatasetController(DatasetService datasetService, EvalRunService evalRunService) {
        this.datasetService = datasetService;
        this.evalRunService = evalRunService;
    }

    private AdminRole requireAdmin(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        return AdminRole.OWNER;
    }

    // ============= Dataset =============

    @PostMapping
    public ResponseEntity<Map<String, Object>> createDataset(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String name = stringOrThrow(body, "name", "name required");
        String description = stringOrNull(body, "description");
        long ownerId = longOrThrow(body, "ownerId", "ownerId required");
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        EvalDataset d = datasetService.create(name, description, tenantId, ownerId, tags, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(d.toMap());
    }

    @GetMapping
    public List<Map<String, Object>> listDatasets(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant) {
        requireAdmin(adminToken);
        return datasetService.findByTenant(tenant).stream().map(EvalDataset::toMap).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getDataset(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        EvalDataset d = datasetService.findById(id);
        Map<String, Object> m = d.toMap();
        m.put("caseCount", datasetService.countCases(id));
        return m;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDataset(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        AdminRole caller = requireAdmin(adminToken);
        boolean ok = datasetService.delete(id, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", ok);
        out.put("id", id);
        return out;
    }

    // ============= Cases =============

    @PostMapping("/{id}/cases")
    public Map<String, Object> importCases(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String jsonl = stringOrThrow(body, "jsonl", "jsonl required");
        int imported = datasetService.importJsonl(id, jsonl, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", imported);
        out.put("datasetId", id);
        out.put("caseCount", datasetService.countCases(id));
        return out;
    }

    @GetMapping("/{id}/cases")
    public List<Map<String, Object>> listCases(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        return datasetService.findCases(id).stream().map(EvalCase::toMap).toList();
    }

    // ============= Run =============

    @PostMapping("/{id}/runs")
    public ResponseEntity<Map<String, Object>> runEval(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        long promptVersionId = longOrThrow(body, "promptVersionId", "promptVersionId required");
        String model = stringOrThrow(body, "model", "model required");
        EvalStrategy strategy;
        try {
            strategy = EvalStrategy.parse(
                    body.get("strategy") == null ? "CONTAINS" : body.get("strategy").toString());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid strategy: " + ex.getMessage());
        }
        long triggeredBy = longOrThrow(body, "triggeredBy", "triggeredBy required");
        EvalRun run;
        try {
            run = evalRunService.run(id, promptVersionId, model, strategy, triggeredBy, caller);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(run.toMap());
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long runId) {
        requireAdmin(adminToken);
        return evalRunService.findById(runId).toMap();
    }

    @GetMapping("/{id}/runs")
    public List<Map<String, Object>> listRuns(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        return evalRunService.findByDataset(id).stream().map(r -> {
            Map<String, Object> m = r.toMap();
            // 列表视图省略 results
            m.remove("results");
            return m;
        }).toList();
    }

    // ============= helpers =============

    private static String stringOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        return v.toString();
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    private static long longOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be integer");
        }
    }
}

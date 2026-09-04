package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.prompt.ABTestService;
import com.company.agentgateway.application.prompt.PromptTemplateService;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.prompt.PromptExperiment;
import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptVariant;
import com.company.agentgateway.domain.prompt.PromptVersion;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板 + 版本 + A/B 实验端点（spec 2026-09-02 §prompt-version §5）。
 *
 * <ul>
 *   <li>Template: {@code POST /v1/admin/prompts} + GET list + GET by id + DELETE</li>
 *   <li>Version: {@code POST /v1/admin/prompts/{id}/versions} + GET versions</li>
 *   <li>Experiment: {@code POST /v1/admin/prompts/{id}/experiments} + GET summary</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/prompts")
public class AdminPromptController {

    private final PromptTemplateService templateService;
    private final ABTestService abService;

    public AdminPromptController(PromptTemplateService templateService, ABTestService abService) {
        this.templateService = templateService;
        this.abService = abService;
    }

    private AdminRole requireAdmin(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        return AdminRole.OWNER; // 兼容旧 token(R13 接 AdminUser 表)
    }

    // ============= Template =============

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTemplate(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String name = stringOrThrow(body, "name", "name required");
        String description = stringOrNull(body, "description");
        long ownerId = longOrThrow(body, "ownerId", "ownerId required");
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        PromptTemplate saved = templateService.create(name, description, ownerId, tenantId, tags, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toMap());
    }

    @GetMapping
    public List<Map<String, Object>> listTemplates(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant) {
        requireAdmin(adminToken);
        return templateService.findByTenant(tenant).stream().map(PromptTemplate::toMap).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTemplate(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        PromptTemplate t = templateService.findById(id);
        Map<String, Object> m = t.toMap();
        m.put("versions", templateService.listVersions(id).stream().map(PromptVersion::toMap).toList());
        return m;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteTemplate(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        AdminRole caller = requireAdmin(adminToken);
        boolean ok = templateService.delete(id, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", ok);
        out.put("id", id);
        return out;
    }

    // ============= Version =============

    @PostMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> addVersion(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String systemPrompt = stringOrNull(body, "systemPrompt");
        String userPrompt = stringOrThrow(body, "userPrompt", "userPrompt required");
        String model = stringOrNull(body, "model");
        long authorId = longOrThrow(body, "authorId", "authorId required");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        PromptVersion v = templateService.addVersion(id, systemPrompt, userPrompt, model, params, authorId, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(v.toMap());
    }

    // ============= Experiment =============

    @PostMapping("/{id}/experiments")
    public ResponseEntity<Map<String, Object>> createExperiment(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String name = stringOrThrow(body, "name", "name required");
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        long createdBy = longOrThrow(body, "createdBy", "createdBy required");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variantsRaw = (List<Map<String, Object>>) body.get("variants");
        if (variantsRaw == null || variantsRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variants required");
        }
        List<PromptVariant> variants = new ArrayList<>();
        for (Map<String, Object> v : variantsRaw) {
            long versionId = longOrThrow(v, "versionId", "versionId required");
            int weight = intOrThrow(v, "weight", "weight required");
            String label = stringOrNull(v, "label");
            try {
                variants.add(new PromptVariant(versionId, weight, label));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid variant: " + ex.getMessage());
            }
        }
        PromptExperiment e;
        try {
            e = abService.createExperiment(id, name, variants, tenantId, createdBy, caller);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(e.toMap());
    }

    @GetMapping("/experiments/{experimentId}/summary")
    public Map<String, Object> getExperimentSummary(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long experimentId) {
        requireAdmin(adminToken);
        ABTestService.ExperimentSummary s = abService.summary(experimentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", s.experimentId());
        out.put("total", s.total());
        out.put("success", s.success());
        out.put("successRate", s.successRate());
        out.put("byVariant", s.byVariant());
        return out;
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

    private static int intOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be integer");
        }
    }
}

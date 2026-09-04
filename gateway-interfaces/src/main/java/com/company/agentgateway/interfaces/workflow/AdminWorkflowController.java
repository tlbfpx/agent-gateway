package com.company.agentgateway.interfaces.workflow;

import com.company.agentgateway.application.workflow.WorkflowParseService;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowDefinition;
import com.company.agentgateway.domain.workflow.WorkflowDefinitionRepository;
import com.company.agentgateway.domain.workflow.WorkflowOrchestrator;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Workflow REST API(spec C1 §8):POST /v1/workflows/run 同步返回 WorkflowRun。
 * JSON / YAML body 由 Content-Type 选择;失败 → 400(校验)/ 200 status=FAILED(运行失败)。
 *
 * <p>鉴权:与 ChatOrchestrator 共享同一 Authenticator / AuthorizationService;
 * 首期通过 @RequestHeader X-API-Key 取 principal(spec B 一致)。
 */
@RestController
@RequestMapping("/v1/workflows")
public class AdminWorkflowController {

    private final WorkflowOrchestrator orchestrator;
    private final WorkflowParseService parser;
    private final WorkflowRepository repository;
    private final WorkflowDefinitionRepository definitionRepository;

    public AdminWorkflowController(WorkflowOrchestrator orchestrator,
                                   WorkflowParseService parser,
                                   @Autowired(required = false) WorkflowRepository repository,
                                   @Autowired(required = false) WorkflowDefinitionRepository definitionRepository) {
        this.orchestrator = orchestrator;
        this.parser = parser;
        this.repository = repository;
        this.definitionRepository = definitionRepository;
    }

    /**
     * 同步执行 workflow:body = { "definitionName": "rag-summary", "inputs": {...} }
     * 或 { "definition": {...}, "inputs": {...} }(内联;definitionName 优先)。
     */
    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowRun> runJson(@RequestBody RunRequest req,
                                                 @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        WorkflowDef def = resolveDefinition(req);
        Map<String, Object> inputs = req.inputs() == null ? Map.of() : req.inputs();
        return ResponseEntity.ok(orchestrator.run(def, inputs,
                com.company.agentgateway.domain.orchestration.InvocationCtx.NOOP));
    }

    @PostMapping(value = "/run", consumes = "application/yaml")
    public ResponseEntity<WorkflowRun> runYaml(@RequestBody String yamlBody,
                                                 @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        WorkflowDef def = parser.parseYaml(yamlBody);
        return ResponseEntity.ok(orchestrator.run(def, Map.of(),
                com.company.agentgateway.domain.orchestration.InvocationCtx.NOOP));
    }

    /**
     * 解析 WorkflowDef:definitionName 优先查 definitionRepository;否则 body 解析 definitionJson。
     * definitionName 不存在 → 404。
     */
    private WorkflowDef resolveDefinition(RunRequest req) {
        if (req.definitionName() != null && !req.definitionName().isBlank()) {
            if (definitionRepository == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "WorkflowDefinitionRepository 未配置(无法按 name 解析)");
            }
            WorkflowDefinition def = definitionRepository.find(req.definitionName())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "workflow definition not found: " + req.definitionName()));
            return def.format() == WorkflowDefinition.Format.YAML
                    ? parser.parseYaml(def.body())
                    : parser.parseJson(def.body());
        }
        return parser.parseJson(req.definitionJson());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<WorkflowRun> get(@PathVariable String runId) {
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WorkflowRepository 未配置(InMemory 默认已被关闭)");
        }
        return repository.find(runId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "workflow run not found: " + runId));
    }

    /** 运行历史(列表):按时间倒序,支持 workflowName/status/range 过滤(均为可选)。 */
    @GetMapping
    public ResponseEntity<List<WorkflowRun>> list(
            @RequestParam(required = false) String workflowName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WorkflowRepository 未配置");
        }
        java.time.Instant to = java.time.Instant.now();
        java.time.Instant from = to.minus(parseRange(range));
        var filter = new com.company.agentgateway.domain.workflow.WorkflowRepository.ListFilter(
                workflowName, status, from, to);
        return ResponseEntity.ok(repository.list(filter, Math.min(limit, 200), Math.max(offset, 0)));
    }

    /** Body = { "definition": {...}, "inputs": {...} } 或 { "definitionName": "rag-summary", "inputs": {...} }。 */
    public record RunRequest(String definitionName, String definitionJson, Map<String, Object> inputs) {}

    // ============ Definition CRUD(C1 §8 扩展) ============

    @GetMapping("/definitions")
    public ResponseEntity<List<WorkflowDefinition>> listDefinitions() {
        if (definitionRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WorkflowDefinitionRepository 未配置");
        }
        return ResponseEntity.ok(definitionRepository.listAll());
    }

    @GetMapping("/definitions/{name}")
    public ResponseEntity<WorkflowDefinition> getDefinition(@PathVariable String name) {
        if (definitionRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WorkflowDefinitionRepository 未配置");
        }
        return definitionRepository.find(name)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "workflow definition not found: " + name));
    }

    @PostMapping(value = "/definitions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDefinition> saveDefinitionJson(@RequestBody DefinitionRequest req) {
        return saveDefinition(req, WorkflowDefinition.Format.JSON);
    }

    @PostMapping(value = "/definitions", consumes = "application/yaml")
    public ResponseEntity<WorkflowDefinition> saveDefinitionYaml(@RequestBody String body) {
        // YAML body 整体 = workflow definition YAML;name 从 body 第一行解析(spec 简化:以 URL name 替代)
        return saveDefinition(new DefinitionRequest(null, null, body, null, null), WorkflowDefinition.Format.YAML);
    }

    @PutMapping(value = "/definitions/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowDefinition> updateDefinition(@PathVariable String name,
                                                             @RequestBody DefinitionRequest req) {
        if (definitionRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置");
        }
        definitionRepository.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "definition not found: " + name));
        var def = new WorkflowDefinition(name, req.description(), req.body(),
                req.format() == null ? WorkflowDefinition.Format.JSON : req.format(),
                null, null, req.createdBy());
        return ResponseEntity.ok(definitionRepository.save(def));
    }

    @DeleteMapping("/definitions/{name}")
    public ResponseEntity<Map<String, Object>> deleteDefinition(@PathVariable String name) {
        if (definitionRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置");
        }
        if (!definitionRepository.delete(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "definition not found: " + name);
        }
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    private ResponseEntity<WorkflowDefinition> saveDefinition(DefinitionRequest req, WorkflowDefinition.Format format) {
        if (definitionRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "definition.name required");
        }
        // 校验 body 实际可解析(spec:保存前 dryRun 验证)
        try {
            if (format == WorkflowDefinition.Format.YAML) {
                parser.parseYaml(req.body());
            } else {
                parser.parseJson(req.body());
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid definition body: " + e.getMessage());
        }
        var def = new WorkflowDefinition(req.name(), req.description(), req.body(), format,
                null, null, req.createdBy());
        return ResponseEntity.ok(definitionRepository.save(def));
    }

    /** Definition 保存请求体:{ name, description?, body, format?, createdBy? }。 */
    public record DefinitionRequest(String name, String description, String body,
                                   WorkflowDefinition.Format format, String createdBy) {}

    private static java.time.Duration parseRange(String range) {
        if (range == null || range.isBlank()) return java.time.Duration.ofHours(24);
        char unit = range.charAt(range.length() - 1);
        long n;
        try {
            n = Long.parseLong(range.substring(0, range.length() - 1));
        } catch (NumberFormatException e) {
            return java.time.Duration.ofHours(24);
        }
        return switch (unit) {
            case 'm' -> java.time.Duration.ofMinutes(n);
            case 'h' -> java.time.Duration.ofHours(n);
            case 'd' -> java.time.Duration.ofDays(n);
            default -> java.time.Duration.ofHours(24);
        };
    }
}
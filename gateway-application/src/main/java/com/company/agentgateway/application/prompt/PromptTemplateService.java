package com.company.agentgateway.application.prompt;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板用例层（spec 2026-09-02 §prompt-version §3.4）。
 *
 * <p>Owner 必须存在;同租户同名唯一;版本号单调递增。
 */
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private final PromptTemplateRepository templateRepo;
    private final PromptVersionRepository versionRepo;

    public PromptTemplateService(
            PromptTemplateRepository templateRepo,
            PromptVersionRepository versionRepo) {
        this.templateRepo = templateRepo;
        this.versionRepo = versionRepo;
    }

    public PromptTemplate create(String name, String description, long ownerId,
                                 String tenantId, List<String> tags, AdminRole callerRole) {
        requireOperator(callerRole);
        templateRepo.findByName(tenantId, name).ifPresent(t -> {
            throw new IllegalStateException("template name already exists: " + name);
        });
        PromptTemplate saved = templateRepo.save(
                PromptTemplate.create(name, description, ownerId, tenantId, tags));
        log.info("prompt.template.created id={} name={} owner={}", saved.id(), name, ownerId);
        return saved;
    }

    public PromptVersion addVersion(long templateId, String systemPrompt, String userPrompt,
                                    String model, Map<String, Object> params, long authorId,
                                    AdminRole callerRole) {
        requireOperator(callerRole);
        PromptTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("template not found: " + templateId));
        int nextVersion = nextVersionNumber(t.id());
        PromptVersion v = versionRepo.save(
                PromptVersion.create(t.id(), nextVersion, systemPrompt, userPrompt,
                        model, params, authorId));
        // 更新 template.updatedAt
        templateRepo.save(t.withUpdatedAt(Instant.now()));
        log.info("prompt.version.created templateId={} version={} author={}", t.id(), nextVersion, authorId);
        return v;
    }

    public PromptTemplate findById(long id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("template not found: " + id));
    }

    public List<PromptVersion> listVersions(long templateId) {
        return versionRepo.findByTemplate(templateId);
    }

    public List<PromptTemplate> findByTenant(String tenantId) {
        return templateRepo.findByTenant(tenantId);
    }

    public boolean delete(long id, AdminRole callerRole) {
        requireAdmin(callerRole);
        boolean ok = templateRepo.delete(id);
        log.info("prompt.template.deleted id={} by={}", id, callerRole);
        return ok;
    }

    private int nextVersionNumber(long templateId) {
        if (versionRepo instanceof com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository memRepo) {
            return memRepo.store().nextVersionNumber(templateId);
        }
        // 兜底:扫描所有 version 取 max + 1
        return versionRepo.findByTemplate(templateId).stream()
                .mapToInt(PromptVersion::version).max().orElse(0) + 1;
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

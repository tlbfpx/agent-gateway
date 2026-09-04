package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;
import com.company.agentgateway.domain.prompt.PromptVersion;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Template + Version 共享存储（spec 2026-09-02 §prompt-version §3.3 P0）。
 *
 * <p>由 {@link InMemoryPromptTemplateRepository} + {@link InMemoryPromptVersionRepository}
 * 共享,保证两边数据一致（如 deleteTemplate 级联删除 versions）。
 */
public class SharedPromptStore {

    private final CopyOnWriteArrayList<PromptTemplate> templates = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PromptVersion> versions = new CopyOnWriteArrayList<>();
    private final AtomicLong nextTemplateId = new AtomicLong(1);
    private final AtomicLong nextVersionId = new AtomicLong(1);

    // ============= Template =============

    public PromptTemplate saveTemplate(PromptTemplate template) {
        if (template.id() == 0) {
            long id = nextTemplateId.getAndIncrement();
            PromptTemplate persisted = new PromptTemplate(
                    id, template.name(), template.description(), template.ownerId(),
                    template.tenantId(), template.tags(), template.createdAt(), template.updatedAt());
            templates.add(persisted);
            return persisted;
        }
        templates.removeIf(t -> t.id() == template.id());
        templates.add(template);
        return template;
    }

    public Optional<PromptTemplate> findTemplateById(long id) {
        return templates.stream().filter(t -> t.id() == id).findFirst();
    }

    public Optional<PromptTemplate> findTemplateByName(String tenantId, String name) {
        return templates.stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    public List<PromptTemplate> findTemplatesByTenant(String tenantId) {
        return templates.stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(PromptTemplate::updatedAt).reversed())
                .toList();
    }

    public List<PromptTemplate> queryTemplates(PromptTemplateRepository.Query q) {
        return templates.stream()
                .filter(t -> q.tenantId() == null || t.tenantId().equals(q.tenantId()))
                .filter(t -> q.ownerId() <= 0 || t.ownerId() == q.ownerId())
                .sorted(Comparator.comparing(PromptTemplate::updatedAt).reversed())
                .skip(q.offset())
                .limit(q.limit())
                .toList();
    }

    public boolean deleteTemplate(long id) {
        int before = templates.size();
        templates.removeIf(t -> t.id() == id);
        // 级联删除 versions
        versions.removeIf(v -> v.templateId() == id);
        return before != templates.size();
    }

    // ============= Version =============

    public PromptVersion saveVersion(PromptVersion version) {
        if (version.id() == 0) {
            long id = nextVersionId.getAndIncrement();
            PromptVersion persisted = new PromptVersion(
                    id, version.templateId(), version.version(), version.systemPrompt(),
                    version.userPrompt(), version.model(), version.params(),
                    version.authorId(), version.createdAt());
            versions.add(persisted);
            return persisted;
        }
        versions.removeIf(v -> v.id() == version.id());
        versions.add(version);
        return version;
    }

    public Optional<PromptVersion> findVersionById(long id) {
        return versions.stream().filter(v -> v.id() == id).findFirst();
    }

    public Optional<PromptVersion> findVersionByVersion(long templateId, int version) {
        return versions.stream()
                .filter(v -> v.templateId() == templateId)
                .filter(v -> v.version() == version)
                .findFirst();
    }

    public List<PromptVersion> findVersionsByTemplate(long templateId) {
        return versions.stream()
                .filter(v -> v.templateId() == templateId)
                .sorted(Comparator.comparingInt(PromptVersion::version).reversed())
                .toList();
    }

    public boolean deleteVersionsByTemplate(long templateId) {
        int before = versions.size();
        versions.removeIf(v -> v.templateId() == templateId);
        return before != versions.size();
    }

    public int nextVersionNumber(long templateId) {
        return versions.stream()
                .filter(v -> v.templateId() == templateId)
                .mapToInt(PromptVersion::version)
                .max()
                .orElse(0) + 1;
    }
}

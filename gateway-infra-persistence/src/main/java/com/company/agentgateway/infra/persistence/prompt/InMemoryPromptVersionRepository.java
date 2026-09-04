package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;

import java.util.List;
import java.util.Optional;

/**
 * PromptVersionRepository 内存实现（spec 2026-09-02 §prompt-version §3.3 P0）。
 */
public class InMemoryPromptVersionRepository implements PromptVersionRepository {

    private final SharedPromptStore store;

    public InMemoryPromptVersionRepository(SharedPromptStore store) {
        this.store = store;
    }

    public InMemoryPromptVersionRepository() { this(new SharedPromptStore()); }

    @Override
    public PromptVersion save(PromptVersion version) {
        return store.saveVersion(version);
    }

    @Override
    public Optional<PromptVersion> findById(long id) {
        return store.findVersionById(id);
    }

    @Override
    public Optional<PromptVersion> findByVersion(long templateId, int version) {
        return store.findVersionByVersion(templateId, version);
    }

    @Override
    public List<PromptVersion> findByTemplate(long templateId) {
        return store.findVersionsByTemplate(templateId);
    }

    @Override
    public boolean deleteByTemplate(long templateId) {
        return store.deleteVersionsByTemplate(templateId);
    }

    /** 暴露内部 store 给 application 层(PromptTemplateService 用于 nextVersionNumber) */
    public SharedPromptStore store() { return store; }
}

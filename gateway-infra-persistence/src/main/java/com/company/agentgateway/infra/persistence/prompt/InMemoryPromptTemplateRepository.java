package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PromptTemplateRepository 内存实现（spec 2026-09-02 §prompt-version §3.3 P0）。
 *
 * <p>与 {@link InMemoryPromptVersionRepository} 共享底层存储
 * （通过 {@link SharedPromptStore} 引用计数共享 lists）。
 * R13 替换为 PgRepository(template / version 两张表)。
 */
public class InMemoryPromptTemplateRepository implements PromptTemplateRepository {

    private final SharedPromptStore store;

    public InMemoryPromptTemplateRepository(SharedPromptStore store) {
        this.store = store;
    }

    public InMemoryPromptTemplateRepository() { this(new SharedPromptStore()); }

    @Override
    public PromptTemplate save(PromptTemplate template) {
        return store.saveTemplate(template);
    }

    @Override
    public Optional<PromptTemplate> findById(long id) {
        return store.findTemplateById(id);
    }

    @Override
    public Optional<PromptTemplate> findByName(String tenantId, String name) {
        return store.findTemplateByName(tenantId, name);
    }

    @Override
    public List<PromptTemplate> findByTenant(String tenantId) {
        return store.findTemplatesByTenant(tenantId);
    }

    @Override
    public List<PromptTemplate> query(Query q) {
        return store.queryTemplates(q);
    }

    @Override
    public boolean delete(long id) {
        return store.deleteTemplate(id);
    }

    /** 暴露内部 store（兼容调用） */
    public SharedPromptStore store() { return store; }
}

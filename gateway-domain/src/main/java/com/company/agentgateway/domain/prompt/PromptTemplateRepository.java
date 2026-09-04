package com.company.agentgateway.domain.prompt;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository {
    PromptTemplate save(PromptTemplate template);
    Optional<PromptTemplate> findById(long id);
    Optional<PromptTemplate> findByName(String tenantId, String name);
    List<PromptTemplate> findByTenant(String tenantId);
    List<PromptTemplate> query(Query q);
    boolean delete(long id);

    record Query(String tenantId, long ownerId, int limit, int offset) {
        public Query {
            limit = limit <= 0 ? 50 : Math.min(limit, 500);
            offset = Math.max(offset, 0);
        }
    }
}

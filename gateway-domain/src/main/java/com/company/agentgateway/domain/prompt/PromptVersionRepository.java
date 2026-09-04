package com.company.agentgateway.domain.prompt;

import java.util.List;
import java.util.Optional;

public interface PromptVersionRepository {
    PromptVersion save(PromptVersion version);
    Optional<PromptVersion> findById(long id);
    /** 同 template 下 version 号唯一;不存在返回 empty */
    Optional<PromptVersion> findByVersion(long templateId, int version);
    /** 某 template 的所有版本,按 version desc 排序 */
    List<PromptVersion> findByTemplate(long templateId);
    boolean deleteByTemplate(long templateId);
}

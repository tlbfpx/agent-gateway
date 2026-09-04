package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 模型注册表接口。
 * <p>
 * 提供模型定义的查询和变更监听能力。
 * <p>
 * 用途：
 * <ul>
 *   <li>ChatClientFactory 通过此接口查询模型定义</li>
 *   <li>ChatClientFactory 监听配置变更，及时缓存失效</li>
 * </ul>
 */
public interface ModelRegistry {

    /**
     * 根据 ID 获取模型定义。
     *
     * @param id 模型 ID
     * @return 模型定义（如存在）
     */
    Optional<ModelDef> getModel(ModelId id);

    /**
     * 列出所有模型定义。
     *
     * @return 模型定义列表（不可变）
     */
    List<ModelDef> listModels();

    /**
     * 注册变更监听器。
     * <p>
     * 当配置变更导致模型定义发生变化时，通知监听器。
     * 监听器收到变更的 ModelId 集合，可用于缓存失效。
     *
     * @param listener 监听器，接收变更的 ModelId 集合
     */
    void addListener(Consumer<Set<ModelId>> listener);
}

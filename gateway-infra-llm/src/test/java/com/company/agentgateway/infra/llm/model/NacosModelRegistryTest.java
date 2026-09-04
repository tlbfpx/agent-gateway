package com.company.agentgateway.infra.llm.model;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NacosModelRegistryTest {

    private static final String DATA_ID = "agent-gateway-models.yaml";
    private static final String GROUP = "GATEWAY";
    private static final long TIMEOUT_MS = 5000;

    private ConfigService mockConfigService;
    private NacosModelRegistry registry;
    private YamlModelConfigParser parser;

    private final String initialYaml = """
            models:
              - id: gpt-4
                provider: openai
                displayName: GPT-4
                endpoint: https://api.openai.com
                apiKeyRef: ${SECRET:OPENAI_API_KEY}
                capabilities: [FUNCTION_CALLING]
                contextWindow: 128000
                costPer1kIn: 0.03
                costPer1kOut: 0.06
                enabled: true
                tenantScope: [all]
            """;

    private final String updatedYaml = """
            models:
              - id: gpt-4
                provider: openai
                displayName: GPT-4 Updated
                endpoint: https://api.openai.com
                apiKeyRef: ${SECRET:OPENAI_API_KEY}
                capabilities: [FUNCTION_CALLING, VISION]
                contextWindow: 128000
                costPer1kIn: 0.03
                costPer1kOut: 0.06
                enabled: true
                tenantScope: [all]
              - id: claude-3-opus
                provider: anthropic
                displayName: Claude 3 Opus
                endpoint: https://api.anthropic.com
                apiKeyRef: ${SECRET:ANTHROPIC_API_KEY}
                capabilities: [FUNCTION_CALLING, VISION]
                contextWindow: 200000
                costPer1kIn: 0.015
                costPer1kOut: 0.075
                enabled: true
                tenantScope: [tenant-a, tenant-b]
            """;

    @BeforeEach
    void setUp() throws NacosException {
        mockConfigService = mock(ConfigService.class);
        parser = new YamlModelConfigParser();

        // Mock 首次 getConfig 返回初始配置
        when(mockConfigService.getConfig(eq(DATA_ID), eq(GROUP), anyLong()))
                .thenReturn(initialYaml);
    }

    @Test
    void shouldLoadInitialConfigOnStartup() throws NacosException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        // 验证 getConfig 被调用
        verify(mockConfigService).getConfig(DATA_ID, GROUP, TIMEOUT_MS);

        // 验证可以查询到模型
        Optional<ModelDef> gpt4 = registry.getModel(new ModelId("gpt-4"));
        assertThat(gpt4).isPresent();
        assertThat(gpt4.get().displayName()).isEqualTo("GPT-4");
        assertThat(gpt4.get().capabilities()).containsExactly(Capability.FUNCTION_CALLING);
    }

    @Test
    void shouldRegisterListenerOnStartup() throws NacosException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        // 验证 addListener 被调用
        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());

        Listener listener = listenerCaptor.getValue();
        assertThat(listener).isNotNull();
        assertThat(listener.getExecutor()).isNull(); // 我们使用 null executor，由 Nacos 默认线程池处理
    }

    @Test
    void shouldUpdateModelsWhenConfigChanges() throws NacosException, InterruptedException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        // 捕获注册的 Listener
        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        Listener listener = listenerCaptor.getValue();

        // 注册变更监听器
        Set<ModelId> changedIds = captureChangedModelIds(listener);

        // 触发配置变更
        listener.receiveConfigInfo(updatedYaml);

        // 等待回调执行
        assertThat(changedIds).isNotNull();

        // 验证变更：gpt-4 被修改，claude-3-opus 被新增
        assertThat(changedIds).containsExactlyInAnyOrder(
                new ModelId("gpt-4"),
                new ModelId("claude-3-opus")
        );

        // 验证模型列表已更新
        List<ModelDef> models = registry.listModels();
        assertThat(models).hasSize(2);

        ModelId gpt4Id = new ModelId("gpt-4");
        Optional<ModelDef> gpt4 = registry.getModel(gpt4Id);
        assertThat(gpt4).isPresent();
        assertThat(gpt4.get().displayName()).isEqualTo("GPT-4 Updated");
        assertThat(gpt4.get().capabilities()).containsExactlyInAnyOrder(
                Capability.FUNCTION_CALLING, Capability.VISION
        );

        ModelId claudeId = new ModelId("claude-3-opus");
        Optional<ModelDef> claude = registry.getModel(claudeId);
        assertThat(claude).isPresent();
        assertThat(claude.get().provider()).isEqualTo("anthropic");
        assertThat(claude.get().tenantScope()).containsExactly("tenant-a", "tenant-b");
    }

    @Test
    void shouldDetectRemovedModels() throws NacosException, InterruptedException {
        // 先加载包含两个模型的配置
        String yamlWithTwoModels = """
                models:
                  - id: model-a
                    provider: test
                    displayName: Model A
                    endpoint: https://api.test.com
                    apiKeyRef: key-a
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                  - id: model-b
                    provider: test
                    displayName: Model B
                    endpoint: https://api.test.com
                    apiKeyRef: key-b
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                """;

        when(mockConfigService.getConfig(eq(DATA_ID), eq(GROUP), anyLong()))
                .thenReturn(yamlWithTwoModels);

        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        Listener listener = listenerCaptor.getValue();

        // 注册监听器捕获变更
        CountDownLatch latch = new CountDownLatch(1);
        final Set<ModelId>[] capturedIds = new Set[1];
        registry.addListener(ids -> {
            capturedIds[0] = ids;
            latch.countDown();
        });

        // 更新为只剩一个模型
        String yamlWithOneModel = """
                models:
                  - id: model-a
                    provider: test
                    displayName: Model A
                    endpoint: https://api.test.com
                    apiKeyRef: key-a
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                """;

        listener.receiveConfigInfo(yamlWithOneModel);

        // 等待回调
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // model-a 被保留（不算变更），model-b 被删除（算变更）
        assertThat(capturedIds[0]).containsExactly(new ModelId("model-b"));

        // 验证 model-b 已不存在
        assertThat(registry.getModel(new ModelId("model-b"))).isEmpty();
        assertThat(registry.getModel(new ModelId("model-a"))).isPresent();
    }

    @Test
    void shouldReturnEmptyOptionalForNonExistentModel() throws NacosException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        Optional<ModelDef> model = registry.getModel(new ModelId("non-existent"));
        assertThat(model).isEmpty();
    }

    @Test
    void shouldReturnAllModels() throws NacosException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        List<ModelDef> models = registry.listModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo(new ModelId("gpt-4"));
    }

    @Test
    void shouldNotifyMultipleListeners() throws NacosException, InterruptedException {
        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        Listener listener = listenerCaptor.getValue();

        // 注册两个监听器
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger callCount1 = new AtomicInteger();
        AtomicInteger callCount2 = new AtomicInteger();

        registry.addListener(ids -> {
            callCount1.incrementAndGet();
            latch1.countDown();
        });

        registry.addListener(ids -> {
            callCount2.incrementAndGet();
            latch2.countDown();
        });

        // 触发配置变更
        listener.receiveConfigInfo(updatedYaml);

        // 等待两个监听器都被调用
        assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(callCount1.get()).isEqualTo(1);
        assertThat(callCount2.get()).isEqualTo(1);
    }

    @Test
    void shouldParseBigDecimalCorrectlyFromYaml() throws NacosException {
        String yamlWithHighPrecision = """
                models:
                  - id: test-model
                    provider: test
                    displayName: Test
                    endpoint: https://api.test.com
                    apiKeyRef: key
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.123456789
                    costPer1kOut: 0.987654321
                    enabled: true
                    tenantScope: [all]
                """;

        when(mockConfigService.getConfig(eq(DATA_ID), eq(GROUP), anyLong()))
                .thenReturn(yamlWithHighPrecision);

        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        Optional<ModelDef> model = registry.getModel(new ModelId("test-model"));
        assertThat(model).isPresent();
        assertThat(model.get().costPer1kIn()).isEqualTo(new BigDecimal("0.123456789"));
        assertThat(model.get().costPer1kOut()).isEqualTo(new BigDecimal("0.987654321"));
    }

    /**
     * 辅助方法：捕获 Listener 回调中的变更 ModelId 集合
     */
    private Set<ModelId> captureChangedModelIds(Listener listener) throws InterruptedException {
        final Set<ModelId>[] capturedIds = new Set[1];
        final CountDownLatch latch = new CountDownLatch(1);

        registry.addListener(ids -> {
            capturedIds[0] = ids;
            latch.countDown();
        });

        // 触发配置变更
        listener.receiveConfigInfo(updatedYaml);

        // 等待回调执行
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        return capturedIds[0];
    }

    @Test
    void shouldClearAllModelsWhenConfigBecomesEmpty() throws NacosException, InterruptedException {
        // 初始：两个模型
        String yamlWithTwoModels = """
                models:
                  - id: model-a
                    provider: test
                    displayName: Model A
                    endpoint: https://api.test.com
                    apiKeyRef: key-a
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                  - id: model-b
                    provider: test
                    displayName: Model B
                    endpoint: https://api.test.com
                    apiKeyRef: key-b
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                """;

        when(mockConfigService.getConfig(eq(DATA_ID), eq(GROUP), anyLong()))
                .thenReturn(yamlWithTwoModels);

        registry = new NacosModelRegistry(mockConfigService, parser, DATA_ID, GROUP, TIMEOUT_MS);

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(mockConfigService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        Listener listener = listenerCaptor.getValue();

        // 验证初始有两个模型
        assertThat(registry.listModels()).hasSize(2);

        // 注册监听器捕获变更
        CountDownLatch latch = new CountDownLatch(1);
        final Set<ModelId>[] capturedIds = new Set[1];
        registry.addListener(ids -> {
            capturedIds[0] = ids;
            latch.countDown();
        });

        // 推送空配置（清空所有模型）
        listener.receiveConfigInfo("");

        // 等待回调
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // 断言：两个原 ModelId 都在 diff 中
        assertThat(capturedIds[0]).containsExactlyInAnyOrder(
                new ModelId("model-a"),
                new ModelId("model-b")
        );

        // 断言：listModels() 变空
        assertThat(registry.listModels()).isEmpty();
        assertThat(registry.getModel(new ModelId("model-a"))).isEmpty();
        assertThat(registry.getModel(new ModelId("model-b"))).isEmpty();
    }
}

package com.company.agentgateway.infra.llm.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.client.config.NacosConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.factory.ChatClientFactory;
import com.company.agentgateway.infra.llm.factory.SecretResolver;
import com.company.agentgateway.infra.llm.factory.EnvSecretResolver;
import com.company.agentgateway.infra.llm.factory.SpringAiChatClientFactory;
import com.company.agentgateway.infra.llm.model.FileModelRegistry;
import com.company.agentgateway.infra.llm.model.ModelRegistry;
import com.company.agentgateway.infra.llm.model.NacosModelRegistry;
import com.company.agentgateway.infra.llm.model.YamlModelConfigParser;
import com.company.agentgateway.infra.llm.port.ChatClientPortImpl;
import com.company.agentgateway.infra.llm.port.ModelCapabilityFailover;
import com.company.agentgateway.infra.llm.provider.ChatModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * infra-llm 运行期装配（可插拔模型接入）。
 *
 * <p>模型来源（按优先级，@ConditionalOnMissingBean 第一个胜出）：
 * <ol>
 *   <li>Nacos（有 nacos.addr）：yaml 配置热更新，生产路径</li>
 *   <li>配置文件（gateway.models.*）：FileModelRegistry，切换模型=改配置零代码</li>
 * </ol>
 *
 * <p>ChatModel 构造经 {@link ChatModelProvider} SPI 路由（每厂商一个 @Component，
 * 新增厂商零改动 Factory）。全链路（registry→factory→failover→port）与模型来源解耦。
 */
@Configuration
@EnableConfigurationProperties(GatewayModelProperties.class)
public class InfraLlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InfraLlmAutoConfiguration.class);

    // ─── 模型注册表（两个来源，先到先得） ───

    @Bean
    @ConditionalOnProperty(name = "nacos.addr")
    public ConfigService nacosConfigService(
            @Value("${nacos.addr}") String serverAddr,
            @Value("${nacos.namespace:}") String namespace,
            @Value("${nacos.username:}") String username,
            @Value("${nacos.password:}") String password) throws NacosException {
        Properties props = new Properties();
        props.setProperty("serverAddr", serverAddr);
        if (namespace != null && !namespace.isBlank()) props.setProperty("namespace", namespace);
        if (username != null && !username.isBlank()) props.setProperty("username", username);
        if (password != null && !password.isBlank()) props.setProperty("password", password);
        return new NacosConfigService(props);
    }

    @Bean
    @ConditionalOnProperty(name = "nacos.addr")
    public YamlModelConfigParser yamlModelConfigParser() {
        return new YamlModelConfigParser();
    }

    /** Nacos 来源（有 nacos.addr 时优先）。 */
    @Bean
    @ConditionalOnProperty(name = "nacos.addr")
    public ModelRegistry nacosModelRegistry(
            ConfigService configService,
            YamlModelConfigParser parser,
            @Value("${gateway.llm.model-config.data-id:agent-gateway-models.yaml}") String dataId,
            @Value("${gateway.llm.model-config.group:GATEWAY}") String group,
            @Value("${gateway.llm.model-config.timeout-ms:5000}") long timeoutMs) throws NacosException {
        return new NacosModelRegistry(configService, parser, dataId, group, timeoutMs);
    }

    /** 可写 JSON 文件来源（无 Nacos 时默认）：管理员 REST 动态增删改，持久化 data/models.json。 */
    @Bean
    @ConditionalOnMissingBean(ModelRegistry.class)
    public ModelRegistry jsonFileModelRegistry(
            @Value("${gateway.llm.registry-file:data/models.json}") String registryFile) {
        return new com.company.agentgateway.infra.llm.model.JsonFileModelRegistry(java.nio.file.Path.of(registryFile));
    }

    /** JsonFileModelRegistry 强类型暴露（AdminModelController 直接注入用）。 */
    @Bean
    @org.springframework.context.annotation.Primary
    public com.company.agentgateway.infra.llm.model.JsonFileModelRegistry jsonFileModelRegistryBean(
            ModelRegistry registry) {
        return (com.company.agentgateway.infra.llm.model.JsonFileModelRegistry) registry;
    }

    // ─── 全链路（与模型来源解耦） ───

    @Bean
    @ConditionalOnMissingBean(SecretResolver.class)
    public SecretResolver secretResolver() {
        return new EnvSecretResolver();
    }

    @Bean
    @ConditionalOnMissingBean(ChatClientFactory.class)
    public ChatClientFactory chatClientFactory(SecretResolver secretResolver,
                                               List<ChatModelProvider> providers,
                                               ModelRegistry registry) {
        SpringAiChatClientFactory factory = new SpringAiChatClientFactory(secretResolver, providers);
        // 热失效桥接：模型配置变更（admin REST / Nacos）→ ChatModel 缓存失效，下次调用按新配置重建
        registry.addListener(changed -> changed.forEach(factory::invalidate));
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(ModelCapabilityFailover.class)
    public ModelCapabilityFailover modelCapabilityFailover(
            ModelRegistry registry,
            @Value("${gateway.llm.fallback-tool-model:}") String fallbackModelId) {
        ModelId fallback = (fallbackModelId == null || fallbackModelId.isBlank())
                ? null : new ModelId(fallbackModelId);
        return new ModelCapabilityFailover(registry, fallback);
    }

    @Bean
    @ConditionalOnMissingBean(ChatClientPort.class)
    public ChatClientPort chatClientPort(
            ModelRegistry registry,
            ChatClientFactory factory,
            ModelCapabilityFailover failover) {
        return new ChatClientPortImpl(registry, factory, failover);
    }

    /**
     * 提示缓存（语义缓存第一步）：内存实现，默认关闭。
     * gateway.llm.prompt-cache.enabled=true 时装配；ttl 默认 10m、max-entries 默认 1000。
     * MeterRegistry 可选（无 actuator/micrometer 环境不记指标）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "gateway.llm.prompt-cache.enabled", havingValue = "true")
    @ConditionalOnMissingBean(com.company.agentgateway.domain.orchestration.PromptCachePort.class)
    public com.company.agentgateway.domain.orchestration.PromptCachePort promptCachePort(
            @Value("${gateway.llm.prompt-cache.ttl:10m}") java.time.Duration ttl,
            @Value("${gateway.llm.prompt-cache.max-entries:1000}") int maxEntries,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        return new com.company.agentgateway.infra.llm.cache.InMemoryPromptCache(
                ttl, maxEntries, meterRegistry.getIfAvailable());
    }
}

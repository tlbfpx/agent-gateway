package com.company.agentgateway.application.config;

import com.company.agentgateway.application.k8s.GatewayReconciler;
import com.company.agentgateway.domain.k8s.K8sGatewayPort;
import com.company.agentgateway.infra.persistence.k8s.InMemoryK8sGatewayStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * K8s CRD 用例层自动装配（Round 14 #k8s-crd）。
 */
@Configuration
public class K8sAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InMemoryK8sGatewayStore.class)
    public InMemoryK8sGatewayStore inMemoryK8sGatewayStore() {
        return new InMemoryK8sGatewayStore();
    }

    @Bean
    @ConditionalOnMissingBean(K8sGatewayPort.class)
    public K8sGatewayPort k8sGatewayPort(InMemoryK8sGatewayStore store) {
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(GatewayReconciler.class)
    public GatewayReconciler gatewayReconciler(K8sGatewayPort port) {
        return new GatewayReconciler(port);
    }
}
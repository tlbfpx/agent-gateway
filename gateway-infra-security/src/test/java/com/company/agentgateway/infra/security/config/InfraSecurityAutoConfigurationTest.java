package com.company.agentgateway.infra.security.config;

import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring 装配：InMemory 仓储是默认 Bean，AuthorizationService 走升级模式构造。
 */
@SpringBootTest(classes = InfraSecurityAutoConfiguration.class)
@TestPropertySource(properties = "spring.main.web-application-type=none")
class InfraSecurityAutoConfigurationTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleBindingRepository roleBindingRepository;

    @Autowired
    private RbacChangePublisher rbacChangePublisher;

    @Autowired
    private AuthorizationService authorizationService;

    @Test
    void inMemoryRepos_areAutoWiredByDefault() {
        assertThat(roleRepository).isInstanceOf(com.company.agentgateway.infra.security.rbac.InMemoryRoleRepository.class);
        assertThat(roleBindingRepository).isInstanceOf(com.company.agentgateway.infra.security.rbac.InMemoryRoleBindingRepository.class);
    }

    @Test
    void nacosChangePublisher_placeholder_isDefault() {
        assertThat(rbacChangePublisher).isInstanceOf(com.company.agentgateway.infra.security.rbac.NacosRbacChangePublisher.class);
    }

    @Test
    void authorizationService_isUpgradedMode_withReposInjected() {
        // 升级模式：决策并集（spec §GW-RBAC-005）
        assertThat(authorizationService).isInstanceOf(com.company.agentgateway.infra.security.AuthorizationServiceImpl.class);
    }
}

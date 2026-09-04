package com.company.agentgateway.application.config;

import com.company.agentgateway.application.admin.AdminUserService;
import com.company.agentgateway.application.admin.TeamService;
import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.domain.iam.admin.TeamRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Admin 用例层自动装配（Round 12 #multi-admin §6 + Round 14 #bcrypt-auth）。
 *
 * <p>依赖由 infra-persistence 模块提供的 Repository Port 实现。
 */
@Configuration
public class AdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AdminUserService.class)
    public AdminUserService adminUserService(AdminUserRepository repository) {
        return new AdminUserService(repository);
    }

    @Bean
    @ConditionalOnMissingBean(TeamService.class)
    public TeamService teamService(TeamRepository teamRepository, AdminUserRepository adminUserRepository) {
        return new TeamService(teamRepository, adminUserRepository);
    }

    @Bean
    @ConditionalOnMissingBean(AdminAuthService.class)
    public AdminAuthService adminAuthService(AdminUserRepository userRepo) {
        return new AdminAuthService(userRepo);
    }
}

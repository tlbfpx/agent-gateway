package com.company.agentgateway.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证 bootstrap 上下文可加载（多模块装配链路通）。 */
@SpringBootTest(properties = {
        // UT 环境关闭 PG 持久化（无 Docker/PG，走 InMemory 降级；集成验证由 -Pit IT 覆盖）
        "observability.storage.enabled=false"
})
class GatewayApplicationTest {
    @Test
    void contextLoads() {
        // 仅验证上下文启动无异常
    }
}

package com.company.agentgateway.interfaces.info;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/ping — 公开 startup 探针（spec §startup-probe round 61）。
 *
 * <p>与 /v1/health 区别：
 * <ul>
 *   <li>/v1/health — liveness：进程存活即 200（已有）</li>
 *   <li>/v1/ready — readiness：依赖就绪才 200（已有）</li>
 *   <li>/v1/ping — 进程启动后任何时候都 200，仅用于"应用是否监听"基础检测</li>
 * </ul>
 *
 * <p>用法：k8s startupProbe 不依赖业务依赖；ready 失败重启时也用 ping 区分。
 */
@RestController
public class PingController {

    @GetMapping("/v1/ping")
    public String ping() {
        return "pong";
    }
}
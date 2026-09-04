package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RBAC Filter（spec §GW-RBAC-010 收敛决议 · §6.3）。
 *
 * <p>唯一承接 {@code check_point=rbac_filter} 的入口。在
 * {@code /v1/chat/{agentName}} 与 {@code /v1/agents/{agentName}/*} 路径调用
 * {@link AuthorizationService#checkInvokeAgent}（checkPoint=RBAC_FILTER）。
 *
 * <p>DENIED：写 403（GW-1003），阻断 FilterChain。ALLOWED / 无 API Key / 非 RBAC 路径：放行
 * （认证缺失由既有认证层兜底；本 Filter 只做纵深防御第二点的 Agent 级预检）。
 */
@Component
@Order(20) // 在认证 Filter 之后
public class RbacFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RbacFilter.class);

    private static final Pattern CHAT_PATH = Pattern.compile("^/v1/chat/([a-zA-Z0-9_-]+).*$");
    private static final Pattern AGENT_PATH = Pattern.compile("^/v1/agents/([a-zA-Z0-9_-]+).*$");

    private final Authenticator authenticator;
    private final AuthorizationService authorizationService;

    public RbacFilter(Authenticator authenticator, AuthorizationService authorizationService) {
        this.authenticator = authenticator;
        this.authorizationService = authorizationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {
        String path = req.getRequestURI();
        String agentName = extractAgentName(path);
        if (agentName == null) {
            // 非 Agent 级 RBAC 路径：放行（模型级/管理端由各自层校验）
            chain.doFilter(req, resp);
            return;
        }
        String apiKey = req.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            // 认证交给既有认证层（401 兜底）；本 Filter 不重复认证
            chain.doFilter(req, resp);
            return;
        }
        final var principal = authenticator.authenticate(apiKey);
        try {
            authorizationService.checkInvokeAgent(principal, agentName, RbacCheckPoint.RBAC_FILTER);
            chain.doFilter(req, resp);
        } catch (AuthorizationException ex) {
            log.warn("RbacFilter DENIED user={} agent={} msg={}",
                    principal.user().value(), agentName, ex.getMessage());
            resp.setStatus(403);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"" + ex.getMessage() + "\"}");
        }
    }

    private String extractAgentName(String path) {
        if (path == null) return null;
        Matcher m = CHAT_PATH.matcher(path);
        if (m.matches()) return m.group(1);
        Matcher m2 = AGENT_PATH.matcher(path);
        if (m2.matches()) return m2.group(1);
        return null;
    }
}

package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 会话管理端点（spec §23.2）。
 * <ul>
 *   <li>POST /v1/sessions：创建会话</li>
 *   <li>GET /v1/sessions/{id}：详情</li>
 *   <li>GET /v1/sessions/{id}/messages：历史分页</li>
 * </ul>
 * 多租户：会话操作强制校验 session.tenant == 调用方 tenant（跨租户拒绝）。
 *
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionApiController {

    private final SessionRepository sessionRepository;
    private final Authenticator authenticator;

    public SessionApiController(SessionRepository sessionRepository, Authenticator authenticator) {
        this.sessionRepository = sessionRepository;
        this.authenticator = authenticator;
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader("X-API-Key") String apiKey,
                                      @RequestParam(defaultValue = "qwen-max") String model) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        Session session = sessionRepository.create(principal.tenant(), principal.user(),
                new com.company.agentgateway.domain.shared.ModelId(model));
        return Map.of("sessionId", session.id().value(), "createdAt", session.createdAt().toString());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id,
                                      @RequestHeader("X-API-Key") String apiKey) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        Session session = sessionRepository.load(new SessionId(id));
        if (session == null || !session.tenant().equals(principal.tenant())) {
            // 不存在与跨租户统一 404，避免探测他人会话 id（原实现返回 200+error）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        return Map.of(
                "sessionId", session.id().value(),
                "model", session.model().value(),
                "messageCount", session.history().size(),
                "createdAt", session.createdAt().toString(),
                "lastActiveAt", session.lastActiveAt().toString());
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id,
                                              @RequestHeader("X-API-Key") String apiKey,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "50") int limit) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        Session session = sessionRepository.load(new SessionId(id));
        if (session == null || !session.tenant().equals(principal.tenant())) {
            return List.of();
        }
        return session.history().stream()
                .skip(offset)
                .limit(limit)
                .map(SessionApiController::messageToMap)
                .toList();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                     @RequestHeader("X-API-Key") String apiKey) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        Session session = sessionRepository.load(new SessionId(id));
        if (session == null || !session.tenant().equals(principal.tenant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        sessionRepository.delete(new SessionId(id));
        return Map.of("deleted", id);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader("X-API-Key") String apiKey,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "20") int limit) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        return sessionRepository.findByUser(principal.tenant(), principal.user(), offset, limit).stream()
                .map(s -> Map.<String, Object>of(
                        "sessionId", s.id().value(),
                        "lastActiveAt", s.lastActiveAt().toString(),
                        "title", titleOf(s),
                        "model", s.model().value(),
                        "messageCount", s.history().size()))
                .toList();
    }

    /** 会话标题：首条用户消息摘要（DeepSeek 式侧栏）。空会话回退「新对话」（不展示 uuid）。 */
    private static String titleOf(Session s) {
        return s.history().stream()
                .filter(m -> m instanceof com.company.agentgateway.domain.session.UserMessage)
                .map(m -> ((com.company.agentgateway.domain.session.UserMessage) m).content())
                .findFirst()
                .map(SessionApiController::summarize)
                .orElse("新对话");
    }

    private static final int TITLE_MAX = 20;

    /** 标题摘要：去行首 markdown 标记/行内反引号 + 折叠空白 + 超 20 字截断。 */
    private static String summarize(String content) {
        String flat = content
                .replaceAll("^[#>*\\-`\\s]+", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (flat.isEmpty()) {
            return "新对话";
        }
        return flat.length() > TITLE_MAX ? flat.substring(0, TITLE_MAX) + "…" : flat;
    }

    private static Map<String, Object> messageToMap(Message m) {
        return switch (m) {
            case com.company.agentgateway.domain.session.UserMessage u -> Map.of("role", "user", "type", "user", "content", u.content());
            case com.company.agentgateway.domain.session.AssistantMessage a -> Map.of("role", "assistant", "type", "assistant", "content", a.content());
            case com.company.agentgateway.domain.session.ToolCallMessage tc -> Map.of("role", "tool", "type", "tool_call", "agent", tc.agentName(), "args", tc.argsJson());
            case com.company.agentgateway.domain.session.ToolResultMessage tr -> Map.of("role", "tool", "type", "tool_result", "agent", tr.agentName(), "content", tr.content(), "slimmed", tr.slimmed());
        };
    }
}

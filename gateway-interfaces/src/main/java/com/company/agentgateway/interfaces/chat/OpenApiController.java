package com.company.agentgateway.interfaces.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.0 路由清单导出（spec §23.4 一期）。
 *
 * <p>轻量实现：手写网关全部端点描述（不引 springdoc——Boot4 适配未验证，见 spike 教训）。
 * 前端类型生成（openapi-typescript）可消费此 JSON。
 */
@RestController
public class OpenApiController {

    @GetMapping("/v1/openapi.json")
    public Map<String, Object> openapi() {
        return Map.of(
                "openapi", "3.0.3",
                "info", Map.of(
                        "title", "Agent Gateway API",
                        "version", "1.0.0",
                        "description", "公司 Agent 通用网关——统一会话入口，A2A 协议调用远程 Agent"),
                "servers", List.of(Map.of("url", "/")),
                "paths", paths(),
                "components", Map.of(
                        "securitySchemes", Map.of(
                                "ApiKeyAuth", Map.of(
                                        "type", "apiKey",
                                        "name", "X-API-Key",
                                        "in", "header"))));
    }

    private Map<String, Object> paths() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        // 第一批（8 对）
        p.putAll(Map.of(
                "/v1/chat", Map.of("post", op("发送消息（非流式）", "ChatRequest", "ChatResponse")),
                "/v1/chat/stream", Map.of("post", opSse()),
                "/v1/sessions", Map.of(
                        "post", op("创建会话", null, "SessionCreated"),
                        "get", op("会话列表", null, "SessionList")),
                "/v1/sessions/{id}", Map.of("get", op("会话详情", null, "SessionDetail")),
                "/v1/sessions/{id}/messages", Map.of("get", op("会话消息历史", null, "MessageList")),
                "/v1/agents", Map.of("get", op("Agent 目录（按授权过滤）", null, "AgentList")),
                "/v1/models", Map.of("get", op("模型列表（管理员配置的启用模型）", null, "ModelList")),
                "/v1/health", Map.of("get", opNoAuth("健康检查"))));
        // 第二批（7 对）
        p.putAll(Map.of(
                "/v1/admin/models", Map.of(
                        "get", op("模型列表（管理）", null, "AdminModelList"),
                        "post", op("新增模型", "AdminModelRequest", "AdminModel")),
                "/v1/admin/models/{id}", Map.of(
                        "put", op("更新模型", "AdminModelRequest", "AdminModel"),
                        "delete", op("删除模型", null, "Deleted")),
                "/v1/admin/api-keys", Map.of(
                        "get", op("占位", null, "Void"),
                        "post", op("签发 API Key", "CreateApiKeyRequest", "ApiKeyCreated")),
                "/v1/admin/api-keys/{key}", Map.of("delete", op("吊销 API Key", null, "Revoked")),
                "/v1/admin/rbac/preview", Map.of("post", op("权限预览", null, "PolicyPreview")),
                "/v1/admin/audit/logs", Map.of("get", op("审计日志查询", null, "AuditLogList")),
                "/v1/openapi.json", Map.of("get", opNoAuth("OpenAPI 规范导出"))));
        // 第三批（OpenAI 兼容端点 — 产品 #1）
        // 不能塞进上面两个 Map.of(...)，Map.of 最多 10 对 KV，已满
        p.put("/v1/chat/completions", Map.of("post",
                op("OpenAI 兼容对话（chat.completions）", "ChatCompletionRequest", "ChatCompletionResponse")));
        p.put("/v1/embeddings", Map.of("post",
                op("OpenAI 兼容向量嵌入（未实现，501 占位）", "EmbeddingRequest", "EmbeddingResponse")));
        return p;
    }

    private Map<String, Object> op(String summary, String reqRef, String respRef) {
        return baseOp(summary, respRef, true);
    }

    private Map<String, Object> opNoAuth(String summary) {
        return baseOp(summary, "Void", false);
    }

    private Map<String, Object> opSse() {
        Map<String, Object> op = baseOp("发送消息（流式 SSE）", "Void", true);
        return op;
    }

    private Map<String, Object> baseOp(String summary, String respRef, boolean secured) {
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("summary", summary);
        op.put("operationId", summary.replaceAll("[^A-Za-z0-9]", ""));
        String ref = "#/components/schemas/" + respRef;
        Map<String, Object> schema = Map.of("$ref", ref);
        Map<String, Object> jsonContent = Map.of("application/json", Map.of("schema", schema));
        Map<String, Object> resp200 = Map.of("description", "OK", "content", jsonContent);
        op.put("responses", Map.of("200", resp200));
        if (secured) {
            op.put("security", List.of(Map.of("ApiKeyAuth", List.of())));
        }
        return op;
    }
}

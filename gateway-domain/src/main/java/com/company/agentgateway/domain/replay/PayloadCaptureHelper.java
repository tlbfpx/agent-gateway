package com.company.agentgateway.domain.replay;

import java.time.Instant;

/**
 * Payload 捕获辅助器(Sprint 2 P0 + P2):纯函数 + 轻依赖(domain 层零框架)。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>{@link #captureRequest(traceId, prompt, model)} — ChatController 入口</li>
 *   <li>{@link #captureResponse(traceId, responseText, tokensIn, tokensOut)} — 响应完成</li>
 *   <li>{@link #captureToolCall(traceId, spanId, toolName, argsJson)} — ChatOrchestrator 工具调用前</li>
 *   <li>{@link #captureToolResult(traceId, spanId, toolName, resultText)} — ChatOrchestrator 工具调用后</li>
 * </ul>
 *
 * <p>所有捕获异步 fire-and-forget(PayloadCapturePort 内部异步);失败仅记录到 console。
 *
 * <p>Sprint 2 重构:从 gateway-interfaces 移到 gateway-domain,解除 application ↔ interfaces 循环依赖;
 * 同时去掉对 ChatRequest record 的依赖,改接收原始字符串以保持 domain 零依赖。
 */
public class PayloadCaptureHelper {

    private final PayloadCapturePort port;

    public PayloadCaptureHelper(PayloadCapturePort port) {
        this.port = port;
    }

    public void captureRequest(String traceId, String prompt, String model) {
        if (traceId == null || port == null) return;
        try {
            String body = "{\"prompt\":\"" + escape(prompt)
                    + "\",\"model\":" + (model == null ? "null" : "\"" + model + "\"")
                    + "}";
            port.capture(new PayloadCapturePort.PayloadRecord(
                    traceId, "", PayloadCapturePort.Role.REQUEST,
                    "messages_json", body, body.getBytes().length, Instant.now()));
        } catch (RuntimeException ignored) { /* port swallows errors */ }
    }

    public void captureResponse(String traceId, String responseText, Integer tokensIn, Integer tokensOut) {
        if (traceId == null || responseText == null || port == null) return;
        try {
            String meta = "{\"tokens_in\":" + (tokensIn == null ? 0 : tokensIn)
                    + ",\"tokens_out\":" + (tokensOut == null ? 0 : tokensOut) + "}";
            port.capture(new PayloadCapturePort.PayloadRecord(
                    traceId, "", PayloadCapturePort.Role.RESPONSE,
                    "text+meta", meta + "\n\n" + responseText,
                    responseText.getBytes().length, Instant.now()));
        } catch (RuntimeException ignored) { /* port swallows errors */ }
    }

    public void captureToolCall(String traceId, String spanId, String toolName, String argsJson) {
        if (traceId == null || port == null) return;
        try {
            String body = "{\"tool\":" + jsonString(toolName) + ",\"args\":" + (argsJson == null ? "{}" : argsJson) + "}";
            port.capture(new PayloadCapturePort.PayloadRecord(
                    traceId, spanId == null ? "" : spanId, PayloadCapturePort.Role.TOOL_CALL,
                    "tool_call_json", body, body.getBytes().length, Instant.now()));
        } catch (RuntimeException ignored) { /* port swallows errors */ }
    }

    public void captureToolResult(String traceId, String spanId, String toolName, String resultText) {
        if (traceId == null || port == null) return;
        try {
            String body = "{\"tool\":" + jsonString(toolName) + ",\"result\":"
                    + (resultText == null ? "null" : jsonString(resultText)) + "}";
            port.capture(new PayloadCapturePort.PayloadRecord(
                    traceId, spanId == null ? "" : spanId, PayloadCapturePort.Role.TOOL_RESULT,
                    "tool_result_json", body, body.getBytes().length, Instant.now()));
        } catch (RuntimeException ignored) { /* port swallows errors */ }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String jsonString(String s) {
        return "\"" + escape(s) + "\"";
    }
}
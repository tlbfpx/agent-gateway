package com.company.agentgateway.domain.observability;

/**
 * 出站内容净化策略 SPI（spec §8.6 安全 / 二期 PII 过滤）。
 * 可插拔：编排器在流式输出前经此过滤。默认 Noop（直通）。
 */
public interface OutputSanitizer {

    /** 净化文本（如 PII 脱敏：手机号/身份证/邮箱 → ****）。实现必须快（逐 chunk 调用）。 */
    String sanitize(String text);

    OutputSanitizer NOOP = text -> text;
}

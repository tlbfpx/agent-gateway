package com.company.agentgateway.domain.safety;

import java.util.regex.Pattern;

/**
 * PII 脱敏工具(Round 9):纯静态函数,domain 零依赖。
 *
 * <p>支持:邮箱 / 手机号 / 身份证 / 银行卡号 替换为 `[REDACTED:TYPE]` 占位符。
 *
 * <p>默认 pattern 来自 {@link DefaultGuardrailLibrary};调用方可传入自定义 pattern。
 */
public final class PiiRedactor {

    private PiiRedactor() {}

    /**
      脱敏。返回替换后的文本与命中数。
      @param text 输入文本(可含 PII)
      @param patterns PII 正则列表(每条匹配整段替换)
      @return RedactionResult { redacted, hitCount }
     */
    public static RedactionResult redact(String text, java.util.List<String> patterns) {
        if (text == null || text.isEmpty() || patterns == null || patterns.isEmpty()) {
            return new RedactionResult(text, 0);
        }
        String current = text;
        int hits = 0;
        for (String p : patterns) {
            Pattern compiled = Pattern.compile(p);
            java.util.regex.Matcher m = compiled.matcher(current);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                hits++;
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("[REDACTED:PI]"));
            }
            m.appendTail(sb);
            current = sb.toString();
        }
        return new RedactionResult(current, hits);
    }

    public record RedactionResult(String redacted, int hitCount) {}
}
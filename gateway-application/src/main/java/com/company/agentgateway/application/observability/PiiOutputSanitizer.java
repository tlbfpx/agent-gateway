package com.company.agentgateway.application.observability;

import com.company.agentgateway.domain.observability.OutputSanitizer;

import java.util.regex.Pattern;

/**
 * PII 输出脱敏（spec §8.6 / 二期）：
 * 手机号 / 身份证(18位) / 邮箱 → 部分打码。逐 chunk 安全（无跨块状态）。
 */
public class PiiOutputSanitizer implements OutputSanitizer {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    @Override
    public String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = PHONE.matcher(text).replaceAll(m -> m.group().substring(0, 3) + "****" + m.group().substring(7));
        s = ID_CARD.matcher(s).replaceAll(m -> m.group().substring(0, 4) + "**********" + m.group().substring(14));
        s = EMAIL.matcher(s).replaceAll(m -> {
            String e = m.group();
            int at = e.indexOf('@');
            return e.charAt(0) + "***" + e.substring(at);
        });
        return s;
    }
}

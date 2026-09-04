package com.company.agentgateway.domain.cache;

import java.util.regex.Pattern;

/**
 * PII 简易检测器(Sprint 4 P0):
 * 检测到身份证 / 手机号 / 银行卡 / 邮箱 / IP 时,直接拒缓存(避免 PII 落到第三方 embedding 服务)。
 *
 * <p>本类是粗粒度兜底;生产环境可扩展接入更完整的 PII 检测服务。
 */
public final class PiiDetector {

    /** 18 位身份证(末位可能 X/x) */
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");
    /** 11 位中国大陆手机号 */
    private static final Pattern MOBILE_CN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    /** 银行卡(13-19 位连续数字,带可选空格) */
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{13,19}\\b|\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b");
    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");
    /** IPv4 */
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private PiiDetector() {}

    /** 检测到任一 PII 即返回 true。 */
    public static boolean containsPii(String text) {
        if (text == null || text.isBlank()) return false;
        return ID_CARD.matcher(text).find()
                || MOBILE_CN.matcher(text).find()
                || BANK_CARD.matcher(text).find()
                || EMAIL.matcher(text).find()
                || IPV4.matcher(text).find();
    }
}
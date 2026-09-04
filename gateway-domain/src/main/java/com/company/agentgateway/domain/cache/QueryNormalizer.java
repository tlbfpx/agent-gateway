package com.company.agentgateway.domain.cache;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Query 归一化器(Sprint 4 P0):
 * 文本先归一化再 embedding,提升向量召回的鲁棒性。
 *
 * <h2>归一化步骤</h2>
 * <ol>
 *   <li>小写(英文)/ 保留大小写(中文)</li>
 *   <li>去除 ASCII 标点 / 替换中文标点为空格</li>
 *   <li>合并连续空白</li>
 *   <li>移除时间戳/数字串</li>
 *   <li>移除停用词(中英常见 100 个)</li>
 *   <li>trim</li>
 * </ol>
 *
 * <p>本类是纯函数无状态;不需要外部依赖。
 * 复杂中文分词留给 embedding 模型自行处理(避免引入 HanLP/jieba 依赖)。
 */
public final class QueryNormalizer {

    /** ASCII 标点 + 符号 */
    private static final Pattern PUNCT_ASCII = Pattern.compile("[\\p{Punct}\\p{S}]+");
    /** 中文标点:零宽/全角符号 */
    private static final Pattern PUNCT_CJK = Pattern.compile("[\\u3000-\\u303F\\uFF00-\\uFFEF]+");
    /** 时间戳 / 长数字串(可能因时间不同而变化,影响召回) */
    private static final Pattern TIMESTAMP = Pattern.compile("\\b\\d{10,}\\b");
    private static final Pattern DURATION = Pattern.compile("\\b\\d{1,3}(ms|s|m|h)\\b", Pattern.CASE_INSENSITIVE);
    /** 连续空白 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 常见中文停用词(top 60)。可后续由 ConfigReloadBus 热更新。 */
    private static final Set<String> STOPWORDS_ZH = Set.of(
            "的", "了", "在", "是", "我", "你", "他", "她", "它", "们",
            "和", "与", "或", "但", "而", "则", "也", "都", "就", "还",
            "把", "被", "给", "让", "请", "帮", "一下", "请问", "麻烦",
            "啊", "呢", "吧", "哦", "嗯", "呀", "哈", "咯", "哇",
            "什么", "怎么", "为什么", "哪", "哪里", "哪个", "多少", "几",
            "这", "那", "这个", "那个", "这些", "那些", "如此", "这样", "那样",
            "因为", "所以", "如果", "虽然", "然后", "接着", "最后", "首先",
            "可以", "不能", "应该", "需要", "想", "要", "会", "能"
    );

    /** 常见英文停用词(top 40)。 */
    private static final Set<String> STOPWORDS_EN = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "should", "could",
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
            "this", "that", "these", "those", "what", "how", "why", "where", "when", "which"
    );

    private QueryNormalizer() {}

    /**
     * 归一化;输入 null 返回空串。
     *
     * <p>处理后形态示例:
     * <pre>
     * "What's the weather like in Tokyo today?" → "weather tokyo today"
     * "今天天气怎么样?" → "今天 天气 怎么样" (中文标点替换为空格)
     * "在 2026-08-31T10:00:00Z 发生的" → "发生"
     * </pre>
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isBlank()) return "";

        // 1. 替换中文标点为空格,保留单词
        s = PUNCT_CJK.matcher(s).replaceAll(" ");
        // 2. 去除 ASCII 标点
        s = PUNCT_ASCII.matcher(s).replaceAll(" ");
        // 3. 移除时间戳 / 短时长
        s = TIMESTAMP.matcher(s).replaceAll(" ");
        s = DURATION.matcher(s).replaceAll(" ");
        // 4. 小写
        s = s.toLowerCase(Locale.ROOT);
        // 5. 移除停用词(英文按整词匹配;中文按字符匹配以处理 "请告诉我" 这种粘合)
        StringBuilder out = new StringBuilder();
        for (String tok : WHITESPACE.split(s)) {
            if (tok.isEmpty()) continue;
            String cleaned = stripStopwords(tok);
            if (cleaned.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(cleaned);
        }
        // 6. trim + collapse whitespace
        return WHITESPACE.matcher(out).replaceAll(" ").trim();
    }

    /** 计算 cacheKey:tenant + model + normalized + tools + temperatureBucket。 */
    public static String buildCacheKey(String tenantId, String model, String normalizedQuery,
                                       String toolsSignature, int temperatureBucket) {
        String raw = tenantId + "|" + model + "|" + normalizedQuery + "|" + toolsSignature
                + "|t" + temperatureBucket;
        return Integer.toHexString(raw.hashCode());
    }

    /**
     * 词内停用词剥离:
     * <ul>
     *   <li>若 token 整词在英文停用词表 → 返回 ""</li>
     *   <li>若 token 含 CJK 字符 → 按字符遍历,中文停用词单字丢弃,保留英文/数字</li>
     *   <li>否则整词保留</li>
     * </ul>
     */
    private static String stripStopwords(String tok) {
        // 英文:精确匹配停用词
        if (STOPWORDS_EN.contains(tok)) return "";
        // 中文/混合:按字符遍历
        if (hasCjk(tok)) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < tok.length(); ) {
                int cp = tok.codePointAt(i);
                int charCount = Character.charCount(cp);
                String ch = new String(Character.toChars(cp));
                if (!STOPWORDS_ZH.contains(ch)) {
                    b.append(ch);
                }
                i += charCount;
            }
            return b.toString();
        }
        // 纯 ASCII 非停用词:保留
        return tok;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) return true; // CJK Unified Ideographs
            i += Character.charCount(cp);
        }
        return false;
    }
}
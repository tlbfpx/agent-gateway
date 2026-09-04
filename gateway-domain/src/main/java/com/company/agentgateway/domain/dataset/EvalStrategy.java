package com.company.agentgateway.domain.dataset;

/**
 * 评测规则策略（spec 2026-09-02 §dataset-eval §4 + §llm-as-judge）。
 *
 * <p>P0 支持 4 种策略：
 * <ul>
 *   <li>{@link #EXACT} - 大小写不敏感精确匹配</li>
 *   <li>{@link #CONTAINS} - 大小写不敏感包含</li>
 *   <li>{@link #REGEX} - 正则匹配(CASE_INSENSITIVE)</li>
 *   <li>{@link #LLM_AS_JUDGE} - 调用 {@link Judge} 端口(R14 #4)</li>
 * </ul>
 */
public enum EvalStrategy {
    EXACT,
    CONTAINS,
    REGEX,
    LLM_AS_JUDGE;

    /**
     * 判断 actualOutput 是否通过规则(LLM_AS_JUDGE 不适用,抛 UOE)。
     */
    public boolean pass(String actualOutput, String expected) {
        if (actualOutput == null) actualOutput = "";
        if (expected == null) expected = "";
        return switch (this) {
            case EXACT -> actualOutput.trim().equalsIgnoreCase(expected.trim());
            case CONTAINS -> actualOutput.toLowerCase().contains(expected.toLowerCase());
            case REGEX -> {
                try {
                    yield java.util.regex.Pattern.compile(expected, java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(actualOutput).find();
                } catch (Exception e) {
                    yield false;
                }
            }
            case LLM_AS_JUDGE -> throw new UnsupportedOperationException(
                    "LLM_AS_JUDGE must be handled via Judge port");
        };
    }

    public static EvalStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("strategy must not be blank");
        }
        try {
            return EvalStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown strategy: " + raw
                    + " (use EXACT|CONTAINS|REGEX|LLM_AS_JUDGE)");
        }
    }
}
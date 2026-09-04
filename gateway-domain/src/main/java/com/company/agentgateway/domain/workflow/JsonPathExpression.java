package com.company.agentgateway.domain.workflow;

/**
 * JSONPath 表达式封装(spec C1 §3.1):仅声明表达式字符串,解析由 JsonPathResolver 实现
 * (infra 层引入 jayway json-path)。domain 保持零框架原则 —— 不在此引用任何 json-path 库。
 *
 * <p>literal 字段(spec C2 补充):非 $ 开头的字符串当字面量值,不参与 JsonPath 解析。
 */
public record JsonPathExpression(String raw, boolean literal) {

    /** JSONPath 表达式(以 $ 开头,infra jayway 解析)。非 $ 开头自动判为字面量。 */
    public JsonPathExpression(String raw) {
        this(raw, raw == null || !raw.startsWith("$"));
    }

    /** 字面量值(spec C2):resolver 直接返回字符串原值。 */
    public static JsonPathExpression literal(String s) {
        return new JsonPathExpression(s, true);
    }

    @Override public String toString() { return raw; }
}
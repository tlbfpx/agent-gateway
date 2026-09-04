package com.company.agentgateway.domain.replay;

import java.util.List;
import java.util.Map;

/**
 * Replay 请求(Sprint 2 P0):把一条历史 trace 重放 + 可选覆盖部分参数。
 *
 * <h2>覆盖字段语义</h2>
 * <ul>
 *   <li>{@code model}:换模型(默认 = 原 trace 的 model)</li>
 *   <li>{@code temperature}/{ topP}/{ maxTokens}:采样参数</li>
 *   <li>{@code messages}:整段替换(默认 = 原 messages)</li>
 *   <li>{@code tools}:tool 列表(默认 = 原 tools)</li>
 * </ul>
 *
 * <h2>安全策略</h2>
 * <ul>
 *   <li>{@code safeReplay=true}(默认):mutating tool 自动跳过,返回占位</li>
 *   <li>{@code safeReplay=false}:mutating tool 真正执行(需显式开启)</li>
 *   <li>{@code allowMutatingTools=true}:即使 safe=true 也强制执行(需要二次审计)</li>
 * </ul>
 *
 * <h2>异步模式</h2>
 * <p>{@code callbackUrl}:长跑 replay 完成后 POST 结果回填;空 = 同步等待
 */
public record ReplayRequest(
        String traceId,
        ReplayOverrides overrides,
        boolean safeReplay,
        boolean allowMutatingTools,
        String callbackUrl,
        Map<String, Object> metadata
) {
    public ReplayRequest {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId required");
        }
        if (overrides == null) overrides = ReplayOverrides.empty();
        // safeReplay=false 但 allowMutatingTools=true → 留痕,允许;
        // 否则默认 safe。
    }

    /** ReplayOverrides:覆盖参数(null 字段表示用原值)。 */
    public record ReplayOverrides(
            String model,
            Double temperature,
            Double topP,
            Integer maxTokens,
            List<Map<String, Object>> messages,
            List<String> tools,
            String system
    ) {
        public static ReplayOverrides empty() {
            return new ReplayOverrides(null, null, null, null, null, null, null);
        }
    }
}
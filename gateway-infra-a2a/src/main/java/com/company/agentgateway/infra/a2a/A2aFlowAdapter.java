package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.orchestration.ToolEvent;

import java.util.concurrent.Flow;
import org.reactivestreams.FlowAdapters;

/**
 * SSE 事件流 → domain Flow.Publisher&lt;ToolEvent&gt; 适配器。
 * 用标准库 {@link FlowAdapters#toFlowPublisher}（reactive-streams 自带），背压/cancel 由契约原生透传。
 *
 * <p>勘误修订4：弃手写 SubmissionPublisher/FlowSubscriptionAdapter，用标准库（与 add-multi-model 一致）。
 */
public final class A2aFlowAdapter {

    private A2aFlowAdapter() {
    }

    /** 把 Reactor Flux&lt;ToolEvent&gt; 适配为 domain 的 Flow.Publisher&lt;ToolEvent&gt;。 */
    public static Flow.Publisher<ToolEvent> toFlow(reactor.core.publisher.Flux<ToolEvent> toolEventFlux) {
        return FlowAdapters.toFlowPublisher(toolEventFlux);
    }
}

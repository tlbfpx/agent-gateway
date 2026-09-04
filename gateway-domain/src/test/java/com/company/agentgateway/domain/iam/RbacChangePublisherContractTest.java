package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RbacChangePublisherContractTest {

    /** 测试用 JDK Flow 桩 */
    static class FlowStub implements RbacChangePublisher {
        final SubmissionPublisher<RbacChangeEvent> pub = new SubmissionPublisher<>();
        @Override public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
            pub.submit(event);
            return pub;
        }
    }

    @Test
    void publish_returnsFlowPublisher_thatEmitsEvent() throws InterruptedException {
        var stub = new FlowStub();
        var received = new AtomicReference<RbacChangeEvent>();
        stub.pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(RbacChangeEvent item) { received.set(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });
        try {
            RbacChangeEvent ev = new RbacChangeEvent(
                    RbacChangeEvent.Kind.ROLE_UPSERT, new TenantId("t1"),
                    new RoleId("r1"), new UserId("u1"), "admin", Instant.now());
            stub.publish(ev);
            Thread.sleep(50); // 等异步分发
            assertThat(received.get()).isNotNull();
            assertThat(received.get().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
        } finally {
            stub.pub.close();
        }
    }
}

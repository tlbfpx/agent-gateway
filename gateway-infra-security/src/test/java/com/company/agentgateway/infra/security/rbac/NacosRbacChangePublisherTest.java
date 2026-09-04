package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class NacosRbacChangePublisherTest {

    @Test
    void publish_returnsFlowPublisher_thatEmitsEvent() throws InterruptedException {
        NacosRbacChangePublisher pub = new NacosRbacChangePublisher();
        AtomicReference<RbacChangeEvent> received = new AtomicReference<>();

        // 先订阅再发布（SubmissionPublisher 不向晚订阅者重放）
        Flow.Publisher<RbacChangeEvent> publisher = pub.asPublisher();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(RbacChangeEvent item) { received.set(item); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        Thread.sleep(50);

        RbacChangeEvent ev = new RbacChangeEvent(
                RbacChangeEvent.Kind.ROLE_UPSERT, new TenantId("t1"),
                new RoleId("r1"), new UserId("u1"), "admin", Instant.now());
        pub.publish(ev);
        Thread.sleep(100);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
    }

    @Test
    void dataId_formatFollowsSpec() {
        NacosRbacChangePublisher pub = new NacosRbacChangePublisher();
        assertThat(pub.dataIdFor(new TenantId("primary")))
                .isEqualTo("gateway.rbac.primary.roles");
    }
}

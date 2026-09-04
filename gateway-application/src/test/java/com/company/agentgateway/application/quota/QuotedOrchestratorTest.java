package com.company.agentgateway.application.quota;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.domain.billing.QuotaExceededException;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuotedOrchestratorTest {

    private final ChatOrchestrator inner = mock(ChatOrchestrator.class);
    private final QuotaGate gate = mock(QuotaGate.class);
    private final QuotedOrchestrator quoted = new QuotedOrchestrator(inner, gate);

    private final ChatRequest request =
            new ChatRequest(null, "hi", new ModelId("m1"));

    @Test
    void gatePasses_delegatesToInner() {
        quoted.orchestrate(request, "key-1", "t1");
        verify(inner).orchestrate(request, "key-1", "t1");
    }

    @Test
    void gatePasses_noTenantHeader_resolvesPrimary() {
        quoted.orchestrate(request, "key-1", null);
        verify(gate).check(any(), any(), any());
        verify(inner).orchestrate(request, "key-1", null);
    }

    @Test
    void quotaGateDenied_propagatesException_innerNotCalled() {
        doThrow(new QuotaExceededException("GW-4304", "quota MODEL_TOKEN exhausted"))
                .when(gate).check(any(), any(), any());
        assertThatThrownBy(() -> quoted.orchestrate(request, "key-1", "t1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("GW-4304");
        verifyNoInteractions(inner);
    }

    @Test
    void tenantSuspended_propagatesGw4305() {
        doThrow(new QuotaExceededException("GW-4305", "tenant t1 suspended"))
                .when(gate).check(any(), any(), any());
        assertThatThrownBy(() -> quoted.orchestrate(request, "key-1", "t1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("GW-4305");
        verifyNoInteractions(inner);
    }

    @Test
    void predictedUsage_isConservativeEstimate() {
        assertThat(QuotedOrchestrator.PREDICTED_USAGE.requests()).isEqualTo(1);
        assertThat(QuotedOrchestrator.PREDICTED_USAGE.tokensIn()).isEqualTo(1000);
        assertThat(QuotedOrchestrator.PREDICTED_USAGE.tokensOut()).isEqualTo(500);
        assertThat(QuotedOrchestrator.PREDICTED_USAGE.cost()).isEqualByComparingTo("0.01");
    }

    @Test
    void fluxPassthrough_contractPreserved() {
        // 装饰器返回类型与 inner 一致（Flux<ChatStreamEvent>），契约不变
        var flux = Flux.<com.company.agentgateway.application.orchestration.ChatStreamEvent>empty();
        org.mockito.Mockito.when(inner.orchestrate(any(), any(), any())).thenReturn(flux);
        assertThat(quoted.orchestrate(request, "k", "t1")).isSameAs(flux);
    }
}

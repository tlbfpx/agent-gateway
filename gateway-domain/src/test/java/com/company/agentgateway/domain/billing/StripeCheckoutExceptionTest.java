package com.company.agentgateway.domain.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StripeCheckoutExceptionTest {

    @Test
    void validationFactory_carriesMessage() {
        StripeCheckoutException ex = StripeCheckoutException.validation("amount must be > 0");
        assertThat(ex.getMessage()).contains("amount must be > 0");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void notFoundFactory_carriesMessage() {
        StripeCheckoutException ex = StripeCheckoutException.notFound("vk_42");
        assertThat(ex.getMessage()).contains("vk_42");
    }

    @Test
    void constructor_supportsCause() {
        Throwable cause = new RuntimeException("network");
        StripeCheckoutException ex = new StripeCheckoutException("wrap", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
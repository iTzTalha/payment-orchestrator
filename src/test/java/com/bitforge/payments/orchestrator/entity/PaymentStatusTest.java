package com.bitforge.payments.orchestrator.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void initiated_canOnlyTransitionTo_processing() {
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.PROCESSING)).isTrue();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.SUCCEEDED)).isFalse();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.FAILED)).isFalse();
        assertThat(PaymentStatus.INITIATED.canTransitionTo(PaymentStatus.INITIATED)).isFalse();
    }

    @Test
    void processing_canTransitionTo_succeededOrFailed() {
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.INITIATED)).isFalse();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.PROCESSING)).isFalse();
    }

    @Test
    void terminalStates_cannotTransitionAnywhere() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(PaymentStatus.SUCCEEDED.canTransitionTo(next))
                    .as("SUCCEEDED -> " + next).isFalse();
            assertThat(PaymentStatus.FAILED.canTransitionTo(next))
                    .as("FAILED -> " + next).isFalse();
        }
    }
}

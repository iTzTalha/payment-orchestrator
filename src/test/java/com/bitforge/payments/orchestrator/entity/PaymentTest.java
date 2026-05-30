package com.bitforge.payments.orchestrator.entity;

import com.bitforge.payments.orchestrator.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void create_assignsIdAndInitiatedStatus() {
        Payment payment = Payment.create("ord-1", 1000L, "USD", PaymentMethod.CARD);

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getMerchantReference()).isEqualTo("ord-1");
        assertThat(payment.getAmountMinor()).isEqualTo(1000L);
        assertThat(payment.getCurrency()).isEqualTo("USD");
        assertThat(payment.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
    }

    @Test
    void transitionTo_advancesStatusForValidMove() {
        Payment payment = Payment.create("ord-1", 1000L, "USD", PaymentMethod.CARD);

        payment.transitionTo(PaymentStatus.PROCESSING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        payment.transitionTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void transitionTo_rejectsIllegalMove_andLeavesStatusUnchanged() {
        Payment payment = Payment.create("ord-1", 1000L, "USD", PaymentMethod.CARD);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.SUCCEEDED))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .satisfies(ex -> {
                    InvalidStatusTransitionException ise = (InvalidStatusTransitionException) ex;
                    assertThat(ise.getFrom()).isEqualTo(PaymentStatus.INITIATED);
                    assertThat(ise.getTo()).isEqualTo(PaymentStatus.SUCCEEDED);
                });

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
    }

    @Test
    void transitionTo_rejectsMoveFromTerminalState() {
        Payment payment = Payment.create("ord-1", 1000L, "USD", PaymentMethod.CARD);
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.FAILED);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.SUCCEEDED))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}

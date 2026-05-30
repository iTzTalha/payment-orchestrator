package com.bitforge.payments.orchestrator.routing;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.exception.UnsupportedPaymentMethodException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingTableTest {

    private static final RoutingDecision CARD_CHAIN = RoutingDecision.of("stripe", "razorpay");
    private static final RoutingDecision UPI_CHAIN = RoutingDecision.of("razorpay", "stripe");

    @Test
    void route_returnsChainForKnownMethod() {
        RoutingTable table = new RoutingTable(Map.of(
                PaymentMethod.CARD, CARD_CHAIN,
                PaymentMethod.UPI, UPI_CHAIN));

        assertThat(table.route(PaymentMethod.CARD).connectorChain())
                .containsExactly("stripe", "razorpay");
        assertThat(table.route(PaymentMethod.UPI).connectorChain())
                .containsExactly("razorpay", "stripe");
    }

    @Test
    void route_throwsForMethodWithoutRule() {
        RoutingTable table = new RoutingTable (Map.of(PaymentMethod.CARD, CARD_CHAIN));

        assertThatThrownBy(() -> table.route(PaymentMethod.WALLET))
                .isInstanceOf(UnsupportedPaymentMethodException.class)
                .hasMessageContaining("WALLET");
    }

    @Test
    void emptyRoutes_areRejectedAtConstruction() {
        assertThatThrownBy(() -> new RoutingTable(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingDecision_rejectsEmptyChain() {
        assertThatThrownBy(() -> new RoutingDecision (List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingDecision_rejectsBlankConnectorId() {
        assertThatThrownBy(() -> RoutingDecision.of("stripe", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingDecision_rejectsDuplicates() {
        assertThatThrownBy(() -> RoutingDecision.of("stripe", "stripe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingDecision_chainIsImmutable() {
        RoutingDecision decision = RoutingDecision.of("stripe", "razorpay");
        assertThatThrownBy(() -> decision.connectorChain().add("paypal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

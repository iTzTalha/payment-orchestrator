package com.bitforge.payments.orchestrator.connector;

import com.bitforge.payments.orchestrator.exception.ConnectorNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorRegistryTest {

    private static final PaymentConnector STRIPE = stub("stripe");
    private static final PaymentConnector RAZORPAY = stub("razorpay");

    @Test
    void byId_returnsRegisteredConnector() {
        ConnectorRegistry registry =
                new ConnectorRegistry(List.of(STRIPE, RAZORPAY));

        assertThat(registry.byId("stripe")).isSameAs(STRIPE);
        assertThat(registry.byId("razorpay")).isSameAs(RAZORPAY);
    }

    @Test
    void byId_throwsForUnknownSlug() {
        ConnectorRegistry registry =
                new ConnectorRegistry(List.of(STRIPE));

        assertThatThrownBy(() -> registry.byId("paypal"))
                .isInstanceOf(ConnectorNotFoundException.class)
                .hasMessageContaining("paypal");
    }

    @Test
    void construction_rejectsTwoConnectorsWithSameId() {
        PaymentConnector duplicate = stub("stripe");

        assertThatThrownBy(() ->
                new ConnectorRegistry(List.of(STRIPE, duplicate)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void construction_acceptsEmptyConnectorList() {
        ConnectorRegistry registry =
                new ConnectorRegistry(List.of());

        assertThatThrownBy(() -> registry.byId("stripe"))
                .isInstanceOf(ConnectorNotFoundException.class);
    }

    private static PaymentConnector stub(String id) {
        return new PaymentConnector() {

            @Override
            public String id() {
                return id;
            }

            @Override
            public ConnectorResponse charge(ConnectorRequest request) {
                return new ConnectorResponse("ref-" + id);
            }
        };
    }
}
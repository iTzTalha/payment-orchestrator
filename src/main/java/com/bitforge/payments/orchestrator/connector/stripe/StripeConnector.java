package com.bitforge.payments.orchestrator.connector.stripe;

import com.bitforge.payments.orchestrator.connector.ConnectorRequest;
import com.bitforge.payments.orchestrator.connector.ConnectorResponse;
import com.bitforge.payments.orchestrator.connector.PaymentConnector;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StripeConnector implements PaymentConnector {

    private static final String ID = "stripe";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConnectorResponse charge(ConnectorRequest request) {
        return new ConnectorResponse("pi_" + UUID.randomUUID().toString().replace("-", ""));
    }
}

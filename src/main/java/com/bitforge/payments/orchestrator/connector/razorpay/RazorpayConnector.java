package com.bitforge.payments.orchestrator.connector.razorpay;

import com.bitforge.payments.orchestrator.connector.ConnectorRequest;
import com.bitforge.payments.orchestrator.connector.ConnectorResponse;
import com.bitforge.payments.orchestrator.connector.PaymentConnector;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RazorpayConnector implements PaymentConnector {

    private static final String ID = "razorpay";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConnectorResponse charge(ConnectorRequest request) {
        return new ConnectorResponse("pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
    }
}

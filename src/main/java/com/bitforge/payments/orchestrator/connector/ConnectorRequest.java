package com.bitforge.payments.orchestrator.connector;

import java.util.UUID;

public record ConnectorRequest(
        UUID paymentId,
        long amountMinor,
        String currency) {

    public ConnectorRequest {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId is required");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
    }
}
package com.bitforge.payments.orchestrator.exception;

public class ConnectorNotFoundException extends RuntimeException {

    public ConnectorNotFoundException(String connectorId) {
        super(
            "No PaymentConnector bean registered for id: "
            + connectorId
        );
    }
}
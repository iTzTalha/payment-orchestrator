package com.bitforge.payments.orchestrator.connector;

public record ConnectorResponse(String connectorReferenceId) {

    public ConnectorResponse {
        if (connectorReferenceId == null || connectorReferenceId.isBlank()) {
            throw new IllegalArgumentException("connectorReferenceId is required");
        }
    }
}
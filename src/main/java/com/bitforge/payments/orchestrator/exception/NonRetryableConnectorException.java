package com.bitforge.payments.orchestrator.exception;

import lombok.Getter;

@Getter
public class NonRetryableConnectorException extends RuntimeException {

    private final String connectorId;

    public NonRetryableConnectorException(
            String connectorId,
            String message) {

        super(message);
        this.connectorId = connectorId;
    }

    public NonRetryableConnectorException(
            String connectorId,
            String message,
            Throwable cause) {

        super(message, cause);
        this.connectorId = connectorId;
    }
}
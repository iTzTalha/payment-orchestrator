package com.bitforge.payments.orchestrator.exception;

import lombok.Getter;

@Getter
public class RetryableConnectorException extends RuntimeException {

    private final String connectorId;

    public RetryableConnectorException(
            String connectorId,
            String message) {

        super(message);
        this.connectorId = connectorId;
    }

    public RetryableConnectorException(
            String connectorId,
            String message,
            Throwable cause) {

        super(message, cause);
        this.connectorId = connectorId;
    }
}
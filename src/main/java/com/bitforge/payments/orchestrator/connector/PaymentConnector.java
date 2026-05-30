package com.bitforge.payments.orchestrator.connector;

public interface PaymentConnector {

    String id();

    ConnectorResponse charge(ConnectorRequest request);
}
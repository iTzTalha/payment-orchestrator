package com.bitforge.payments.orchestrator.connector;

import com.bitforge.payments.orchestrator.exception.ConnectorNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ConnectorRegistry {

    private final Map<String, PaymentConnector> connectorsById;

    public ConnectorRegistry(List<PaymentConnector> connectors) {

        this.connectorsById = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(
                        PaymentConnector::id,
                        Function.identity()));
    }

    public PaymentConnector byId(String connectorId) {

        PaymentConnector connector = connectorsById.get(connectorId);

        if (connector == null) {
            throw new ConnectorNotFoundException(connectorId);
        }

        return connector;
    }
}
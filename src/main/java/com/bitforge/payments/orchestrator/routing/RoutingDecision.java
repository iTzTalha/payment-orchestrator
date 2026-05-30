package com.bitforge.payments.orchestrator.routing;

import java.util.List;

public record RoutingDecision(List<String> connectorChain) {

    public RoutingDecision {
        if (connectorChain == null || connectorChain.isEmpty()) {
            throw new IllegalArgumentException("connectorChain must contain at least one connector id");
        }

        for (String id : connectorChain) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("connectorChain must not contain blank ids");
            }
        }

        if (connectorChain.stream().distinct().count() != connectorChain.size()) {
            throw new IllegalArgumentException("connectorChain must not contain duplicates");
        }

        connectorChain = List.copyOf(connectorChain);
    }

    public static RoutingDecision of(String... connectorIds) {
        return new RoutingDecision(List.of(connectorIds));
    }
}
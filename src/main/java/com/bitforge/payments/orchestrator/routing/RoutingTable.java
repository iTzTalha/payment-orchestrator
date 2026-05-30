package com.bitforge.payments.orchestrator.routing;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.exception.UnsupportedPaymentMethodException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record RoutingTable(Map<PaymentMethod, RoutingDecision> routes) {

    public RoutingTable {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }

        routes = Collections.unmodifiableMap(new EnumMap<>(routes));
    }

    public RoutingDecision route(PaymentMethod method) {
        RoutingDecision decision = routes.get(method);

        if (decision == null) {
            throw new UnsupportedPaymentMethodException(method);
        }

        return decision;
    }
}
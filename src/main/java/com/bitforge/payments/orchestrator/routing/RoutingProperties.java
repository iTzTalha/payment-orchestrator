package com.bitforge.payments.orchestrator.routing;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("orchestrator.routing")
public record RoutingProperties(Map<PaymentMethod, List<String>> rules) {

    public RoutingProperties {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("orchestrator.routing.rules must not be empty");
        }

        rules.forEach((PaymentMethod method, List<String> chain) -> {
            if (chain == null || chain.isEmpty()) {
                throw new IllegalArgumentException("orchestrator.routing.rules." + method + " must not be empty");
            }
        });
    }
}
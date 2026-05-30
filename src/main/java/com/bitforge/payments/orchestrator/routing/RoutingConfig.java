package com.bitforge.payments.orchestrator.routing;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
class RoutingConfig {

    @Bean
    RoutingTable routingTable(RoutingProperties properties) {
        Map<PaymentMethod, RoutingDecision> routes = new EnumMap<>(PaymentMethod.class);

        properties.rules().forEach((PaymentMethod method, java.util.List<String> chain) ->
                        routes.put(method, new RoutingDecision(chain)));

        return new RoutingTable(routes);
    }
}
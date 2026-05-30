package com.bitforge.payments.orchestrator.service;

import com.bitforge.payments.orchestrator.connector.ConnectorInvoker;
import com.bitforge.payments.orchestrator.connector.ConnectorRegistry;
import com.bitforge.payments.orchestrator.connector.ConnectorRequest;
import com.bitforge.payments.orchestrator.connector.PaymentConnector;
import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.entity.PaymentAttempt;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import com.bitforge.payments.orchestrator.exception.NonRetryableConnectorException;
import com.bitforge.payments.orchestrator.exception.RetryableConnectorException;
import com.bitforge.payments.orchestrator.repository.PaymentAttemptRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import com.bitforge.payments.orchestrator.routing.RoutingDecision;
import com.bitforge.payments.orchestrator.routing.RoutingTable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class PaymentProcessor {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RoutingTable routingTable;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorInvoker connectorInvoker;

    @Value("${orchestrator.retry.failover-on-non-retryable:true}")
    private boolean failoverOnNonRetryable;

    public void process(Payment payment) {
        payment.transitionTo(PaymentStatus.PROCESSING);

        RoutingDecision decision = routingTable.route(payment.getPaymentMethod());
        ConnectorRequest request = new ConnectorRequest(payment.getId(), payment.getAmountMinor(), payment.getCurrency());

        int attemptNumber = 0;
        for (String connectorId : decision.connectorChain()) {
            attemptNumber++;
            PaymentConnector connector = connectorRegistry.byId(connectorId);
            Instant startedAt = Instant.now();
            try {
                connectorInvoker.invoke(connector, request);
                paymentAttemptRepository.save(PaymentAttempt.succeeded(payment.getId(), attemptNumber, connectorId, startedAt));
                payment.transitionTo(PaymentStatus.SUCCEEDED);
                paymentRepository.save(payment);
                return;
            } catch (NonRetryableConnectorException ex) {
                paymentAttemptRepository.save(PaymentAttempt.failedNonRetryable(payment.getId(), attemptNumber, connectorId, startedAt, ex.getMessage()));
                if (!failoverOnNonRetryable) {
                    break;
                }
            } catch (RetryableConnectorException ex) {
                paymentAttemptRepository.save(PaymentAttempt.failedRetryable(payment.getId(), attemptNumber, connectorId, startedAt, ex.getMessage()));
            }
        }

        payment.transitionTo(PaymentStatus.FAILED);
        paymentRepository.save(payment);
    }
}
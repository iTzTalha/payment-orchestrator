package com.bitforge.payments.orchestrator.connector;

import com.bitforge.payments.orchestrator.exception.NonRetryableConnectorException;
import com.bitforge.payments.orchestrator.exception.RetryableConnectorException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class ConnectorInvoker {

    @Retryable(
            retryFor = RetryableConnectorException.class,
            noRetryFor = NonRetryableConnectorException.class,
            maxAttemptsExpression = "${orchestrator.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${orchestrator.retry.initial-backoff-ms:200}",
                    multiplierExpression = "${orchestrator.retry.backoff-multiplier:2.0}",
                    maxDelayExpression = "${orchestrator.retry.max-backoff-ms:2000}"
            )
    )
    public ConnectorResponse invoke(PaymentConnector connector, ConnectorRequest request) {
        return connector.charge(request);
    }
}
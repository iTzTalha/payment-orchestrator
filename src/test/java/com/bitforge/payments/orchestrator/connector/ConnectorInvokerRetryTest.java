package com.bitforge.payments.orchestrator.connector;

import com.bitforge.payments.orchestrator.exception.NonRetryableConnectorException;
import com.bitforge.payments.orchestrator.exception.RetryableConnectorException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@ContextConfiguration(classes = ConnectorInvokerRetryTest.Config.class)
@TestPropertySource(properties = {
    "orchestrator.retry.max-attempts=3",
    "orchestrator.retry.initial-backoff-ms=1",
    "orchestrator.retry.backoff-multiplier=1.0",
    "orchestrator.retry.max-backoff-ms=2"
})
class ConnectorInvokerRetryTest {

    @EnableRetry
    @Configuration
    static class Config {

        @Bean
        ConnectorInvoker connectorInvoker() {
            return new ConnectorInvoker();
        }
    }

    @Autowired
    private ConnectorInvoker invoker;

    private PaymentConnector connector;
    private ConnectorRequest request;

    @BeforeEach
    void setUp() {
        connector = mock(PaymentConnector.class);

        when(connector.id()).thenReturn("stripe");

        request = new ConnectorRequest(
                UUID.randomUUID(),
                1000L,
                "USD"
        );
    }

    @Test
    void retryable_isRetried_uptoMaxAttempts() {
        when(connector.charge(any()))
                .thenThrow(new RetryableConnectorException("stripe", "boom"));

        assertThatThrownBy(() -> invoker.invoke(connector, request))
                .isInstanceOf(RetryableConnectorException.class);

        verify(connector, times(3)).charge(any());
    }

    @Test
    void retryable_thenSuccess_returnsResponseAndStopsRetrying() {
        when(connector.charge(any()))
                .thenThrow(new RetryableConnectorException("stripe", "transient"))
                .thenReturn(new ConnectorResponse("pi_OK"));

        ConnectorResponse response = invoker.invoke(connector, request);

        assertThat(response.connectorReferenceId()).isEqualTo("pi_OK");
        verify(connector, times(2)).charge(any());
    }

    @Test
    void nonRetryable_isThrownImmediately_withoutRetry() {
        when(connector.charge(any()))
                .thenThrow(new NonRetryableConnectorException("stripe", "card declined"));

        assertThatThrownBy(() -> invoker.invoke(connector, request))
                .isInstanceOf(NonRetryableConnectorException.class);

        verify(connector, times(1)).charge(any());
    }

    @Test
    void unrelatedException_isNotRetried() {
        when(connector.charge(any()))
                .thenThrow(new IllegalStateException("programmer error"));

        assertThatThrownBy(() -> invoker.invoke(connector, request))
                .isInstanceOf(IllegalStateException.class);

        verify(connector, times(1)).charge(any());
        verify(connector, never()).id();
    }
}
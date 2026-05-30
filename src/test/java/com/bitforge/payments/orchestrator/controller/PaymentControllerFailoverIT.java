package com.bitforge.payments.orchestrator.controller;

import com.bitforge.payments.orchestrator.connector.ConnectorResponse;
import com.bitforge.payments.orchestrator.connector.razorpay.RazorpayConnector;
import com.bitforge.payments.orchestrator.connector.stripe.StripeConnector;
import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.entity.AttemptStatus;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import com.bitforge.payments.orchestrator.exception.NonRetryableConnectorException;
import com.bitforge.payments.orchestrator.exception.RetryableConnectorException;
import com.bitforge.payments.orchestrator.repository.IdempotencyRecordRepository;
import com.bitforge.payments.orchestrator.repository.PaymentAttemptRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "orchestrator.retry.max-attempts=3")
class PaymentControllerFailoverIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;

    @MockitoSpyBean private StripeConnector stripe;
    @MockitoSpyBean private RazorpayConnector razorpay;

    @AfterEach
    void cleanup() {
        idempotencyRecords.deleteAll();
        attempts.deleteAll();
        payments.deleteAll();
    }

    @Test
    void primaryTransientThenSucceeds_oneAttemptRowOnStripe() throws Exception {
        doThrow(new RetryableConnectorException("stripe", "blip"))
                .doReturn(new ConnectorResponse("pi_OK"))
                .when(stripe).charge(any());

        UUID id = postCardPaymentExpecting(PaymentStatus.SUCCEEDED, "i07");

        verify(stripe, times(2)).charge(any());
        verify(razorpay, times(0)).charge(any());

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getConnectorId()).isEqualTo("stripe");
        assertThat(rows.get(0).getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
    }

    @Test
    void primaryExhaustsRetries_failsOverToRazorpay() throws Exception {
        doThrow(new RetryableConnectorException("stripe", "boom"))
                .when(stripe).charge(any());
        doReturn(new ConnectorResponse("pay_OK"))
                .when(razorpay).charge(any());

        UUID id = postCardPaymentExpecting(PaymentStatus.SUCCEEDED, "i08");

        verify(stripe, times(3)).charge(any());
        verify(razorpay, times(1)).charge(any());

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getConnectorId()).isEqualTo("stripe");
        assertThat(rows.get(0).getStatus()).isEqualTo(AttemptStatus.FAILED_RETRYABLE);
        assertThat(rows.get(1).getConnectorId()).isEqualTo("razorpay");
        assertThat(rows.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
    }

    @Test
    void bothConnectorsFail_paymentEndsFailed_with2AttemptRows() throws Exception {
        doThrow(new RetryableConnectorException("stripe", "down"))
                .when(stripe).charge(any());
        doThrow(new RetryableConnectorException("razorpay", "down"))
                .when(razorpay).charge(any());

        UUID id = postCardPaymentExpecting(PaymentStatus.FAILED, "i09");

        verify(stripe, times(3)).charge(any());
        verify(razorpay, times(3)).charge(any());

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getStatus() == AttemptStatus.FAILED_RETRYABLE);
    }

    @Test
    void primaryNonRetryable_failoverOnByDefault_succeedsOnRazorpay() throws Exception {
        doThrow(new NonRetryableConnectorException("stripe", "card_declined"))
                .when(stripe).charge(any());
        doReturn(new ConnectorResponse("pay_OK"))
                .when(razorpay).charge(any());

        UUID id = postCardPaymentExpecting(PaymentStatus.SUCCEEDED, "i10");

        verify(stripe, times(1)).charge(any());
        verify(razorpay, times(1)).charge(any());

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttemptStatus.FAILED_NON_RETRYABLE);
        assertThat(rows.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
    }

    private UUID postCardPaymentExpecting(PaymentStatus expected, String idempotencyTag) throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-" + idempotencyTag, 1000L, "USD", PaymentMethod.CARD);

        String response = mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "failover-key-" + idempotencyTag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(response).get("id").asText());
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(expected);
        return id;
    }
}

package com.bitforge.payments.orchestrator.controller;

import com.bitforge.payments.orchestrator.connector.razorpay.RazorpayConnector;
import com.bitforge.payments.orchestrator.connector.stripe.StripeConnector;
import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.entity.AttemptStatus;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import com.bitforge.payments.orchestrator.exception.NonRetryableConnectorException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "orchestrator.retry.failover-on-non-retryable=false")
class PaymentControllerNoFailoverIT {

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
    void primaryNonRetryable_failoverOff_paymentFailed_andRazorpayUntouched() throws Exception {
        doThrow(new NonRetryableConnectorException("stripe", "card_declined"))
                .when(stripe).charge(any());

        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-111", 1000L, "USD", PaymentMethod.CARD);

        String response = mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "fail-off-111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(response).get("id").asText());

        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(stripe, times(1)).charge(any());
        verify(razorpay, times(0)).charge(any());

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getConnectorId()).isEqualTo("stripe");
        assertThat(rows.getFirst().getStatus()).isEqualTo(AttemptStatus.FAILED_NON_RETRYABLE);
    }
}

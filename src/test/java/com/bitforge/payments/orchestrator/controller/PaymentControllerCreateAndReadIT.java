package com.bitforge.payments.orchestrator.controller;

import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerCreateAndReadIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;

    @AfterEach
    void cleanup() {
        idempotencyRecords.deleteAll();
        attempts.deleteAll();
        payments.deleteAll();
    }

    @Test
    void postCardPayment_returns201_succeededViaFirstConnector_andRecordsOneAttempt() throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-1", 1000L, "USD", PaymentMethod.CARD);

        String responseBody = mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "card-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.merchantReference").value("ord-1"))
                .andExpect(jsonPath("$.amountMinor").value(1000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.connectorId").doesNotExist())
                .andExpect(jsonPath("$.attempts").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(responseBody).get("id").asText());
        Payment persisted = payments.findById(id).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getConnectorId()).isEqualTo("stripe");
        assertThat(rows.get(0).getStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void postUpiPayment_routesToRazorpayFirst() throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-2", 5000L, "INR", PaymentMethod.UPI);

        String responseBody = mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "upi-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(responseBody).get("id").asText());
        var rows = attempts.findByPaymentIdOrderByAttemptNumberAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getConnectorId()).isEqualTo("razorpay");
    }

    @Test
    void post_unsupportedPaymentMethod_returns422() throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-x", 100L, "USD", PaymentMethod.WALLET);

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "wallet-key01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void post_missingIdempotencyKey_returns400() throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                "ord-1", 1000L, "USD", PaymentMethod.CARD);

        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void get_existing_returnsSlimResponse() throws Exception {
        UUID id = createPayment("ord-get-1");

        mvc.perform(get("/api/v1/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts").doesNotExist());
    }

    @Test
    void get_malformedId_returns400Problem() throws Exception {
        mvc.perform(get("/api/v1/payments/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void get_unknownId_returns404Problem() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getAttempts_returnsOrderedList() throws Exception {
        UUID id = createPayment("ord-att-1");

        mvc.perform(get("/api/v1/payments/{id}/attempts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].connectorId").value("stripe"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void getAttempts_forUnknownId_returns404() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}/attempts", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private UUID createPayment(String merchantRef) throws Exception {
        CreatePaymentRequest body = new CreatePaymentRequest(
                merchantRef, 1000L, "USD", PaymentMethod.CARD);

        String response = mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "seed-" + UUID.randomUUID().toString().substring(0, 8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(json.readTree(response).get("id").asText());
    }
}

package com.bitforge.payments.orchestrator.controller;

import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.repository.IdempotencyRecordRepository;
import com.bitforge.payments.orchestrator.repository.PaymentAttemptRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import com.bitforge.payments.orchestrator.service.IdempotencyCleanupJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "orchestrator.idempotency.ttl=PT0.1S")
class PaymentControllerIdempotencyIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private IdempotencyCleanupJob cleanupJob;

    private static final CreatePaymentRequest REQUEST = new CreatePaymentRequest(
            "ord-1", 1000L, "USD", PaymentMethod.CARD);

    private static final CreatePaymentRequest DIFFERENT_REQUEST = new CreatePaymentRequest(
            "ord-2", 2000L, "USD", PaymentMethod.CARD);

    @AfterEach
    void cleanup() {
        idempotencyRecords.deleteAll();
        attempts.deleteAll();
        payments.deleteAll();
    }

    @Test
    void replaysSameKeySameBody_returnsSamePaymentId_andNoNewRow() throws Exception {
        String key = "replay-key-01";

        String firstBody = postPayment(key, REQUEST, status().isCreated());
        String firstId = json.readTree(firstBody).get("id").asText();
        assertThat(payments.count()).isEqualTo(1);

        String secondBody = postPayment(key, REQUEST, status().isCreated());
        String secondId = json.readTree(secondBody).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_returns409Problem() throws Exception {
        String key = "conflict-key0";

        postPayment(key, REQUEST, status().isCreated());

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(DIFFERENT_REQUEST)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void expiredKey_isReusableAsNew_afterCleanup() throws Exception {
        String key = "expiry-key-01";

        String firstBody = postPayment(key, REQUEST, status().isCreated());
        String firstId = json.readTree(firstBody).get("id").asText();

        Thread.sleep(150);
        cleanupJob.purgeExpired();
        assertThat(idempotencyRecords.findById(key)).isEmpty();

        String secondBody = postPayment(key, REQUEST, status().isCreated());
        String secondId = json.readTree(secondBody).get("id").asText();

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(payments.count()).isEqualTo(2);
    }

    private String postPayment(String key, CreatePaymentRequest body, ResultMatcher expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(expectedStatus)
                .andReturn().getResponse().getContentAsString();
    }
}

package com.bitforge.payments.orchestrator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerValidationIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void malformedJson_returns400Problem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not really json }"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void wrongContentType_returns415Problem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownPaymentMethodEnum_returns400() throws Exception {
        String body = """
                {
                    "merchantReference": "ord-1",
                    "amountMinor": 1000,
                    "currency": "USD",
                    "paymentMethod": "NETBANKING"
                }
                """;

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void negativeAmount_returns400_withErrorsMap() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("amountMinor", -1);

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }

    @Test
    void zeroAmount_returns400_withErrorsMap() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("amountMinor", 0);

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }

    @Test
    void lowercaseCurrency_returns400_withErrorsMap() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("currency", "usd");

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void blankMerchantReference_returns400_withErrorsMap() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("merchantReference", " ");

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "valid-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.merchantReference").exists());
    }

    @Test
    void shortIdempotencyKey_returns400() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(baseBody())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void tooLongIdempotencyKey_returns400() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "x".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(baseBody())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantReference", "ord-1");
        body.put("amountMinor", 1000);
        body.put("currency", "USD");
        body.put("paymentMethod", "CARD");
        return body;
    }
}

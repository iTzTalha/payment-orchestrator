package com.bitforge.payments.orchestrator.controller;

import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.dto.PaymentAttemptResponse;
import com.bitforge.payments.orchestrator.dto.PaymentResponse;
import com.bitforge.payments.orchestrator.service.IdempotencyService;
import com.bitforge.payments.orchestrator.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(
            @RequestHeader("Idempotency-Key")
            @NotBlank
            @Size(min = 8, max = 128)
            String idempotencyKey,

            @Valid
            @RequestBody
            CreatePaymentRequest request) {

        return idempotencyService.executeIdempotent(
                idempotencyKey,
                request,
                () -> paymentService.create(request)
        );
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.findById(id);
    }

    @GetMapping("/{id}/attempts")
    public List<PaymentAttemptResponse> attempts(@PathVariable UUID id) {
        return paymentService.findAttempts(id);
    }
}
package com.bitforge.payments.orchestrator.dto;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import jakarta.validation.constraints.*;

public record CreatePaymentRequest(

        @NotBlank
        @Size(max = 64)
        String merchantReference,

        @NotNull
        @Positive
        Long amountMinor,

        @NotBlank
        @Pattern(
                regexp = "^[A-Z]{3}$",
                message = "must be a 3-letter ISO-4217 code"
        )
        String currency,

        @NotNull
        PaymentMethod paymentMethod

) {
}
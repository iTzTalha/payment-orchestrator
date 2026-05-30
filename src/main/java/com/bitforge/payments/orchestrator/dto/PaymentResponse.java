package com.bitforge.payments.orchestrator.dto;

import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(

        UUID id,
        String merchantReference,
        long amountMinor,
        String currency,
        PaymentMethod paymentMethod,
        PaymentStatus status

) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantReference(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getPaymentMethod(),
                payment.getStatus()
        );
    }
}
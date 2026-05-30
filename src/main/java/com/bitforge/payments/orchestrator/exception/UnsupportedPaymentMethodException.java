package com.bitforge.payments.orchestrator.exception;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;

public class UnsupportedPaymentMethodException extends RuntimeException {

    public UnsupportedPaymentMethodException(PaymentMethod method) {
        super("No routing configured for payment method: " + method);
    }
}
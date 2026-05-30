package com.bitforge.payments.orchestrator.exception;

import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import lombok.Getter;

@Getter
public class InvalidStatusTransitionException extends RuntimeException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public InvalidStatusTransitionException(
            PaymentStatus from,
            PaymentStatus to) {

        super("Illegal status transition: " + from + " -> " + to);

        this.from = from;
        this.to = to;
    }
}
package com.bitforge.payments.orchestrator.exception;

public class IdempotencyKeyConflictException
        extends RuntimeException {

    public IdempotencyKeyConflictException(
            String key,
            String reason) {

        super(
            "Idempotency key '"
            + key
            + "' conflict: "
            + reason
        );
    }
}
package com.bitforge.payments.orchestrator.dto;

import com.bitforge.payments.orchestrator.entity.AttemptStatus;
import com.bitforge.payments.orchestrator.entity.PaymentAttempt;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentAttemptResponse(

        UUID id,
        int attemptNumber,
        String connectorId,
        AttemptStatus status,
        String errorMessage,
        Instant startedAt,
        Instant endedAt

) {

    public static PaymentAttemptResponse from(PaymentAttempt attempt) {
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getConnectorId(),
                attempt.getStatus(),
                attempt.getErrorMessage(),
                attempt.getStartedAt(),
                attempt.getEndedAt()
        );
    }
}
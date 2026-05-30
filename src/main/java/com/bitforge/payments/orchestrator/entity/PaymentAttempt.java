package com.bitforge.payments.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_attempts",
        indexes = {
                @Index(
                        name = "idx_payment_attempts_payment_id",
                        columnList = "payment_id, attempt_number"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false, length = 64)
    private String connectorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttemptStatus status;

    @Column(length = 1024)
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant endedAt;

    public static PaymentAttempt succeeded(
            UUID paymentId,
            int attemptNumber,
            String connectorId,
            Instant startedAt
    ) {
        return build(
                paymentId,
                attemptNumber,
                connectorId,
                startedAt,
                AttemptStatus.SUCCEEDED,
                null
        );
    }

    public static PaymentAttempt failedRetryable(
            UUID paymentId,
            int attemptNumber,
            String connectorId,
            Instant startedAt,
            String errorMessage
    ) {
        return build(
                paymentId,
                attemptNumber,
                connectorId,
                startedAt,
                AttemptStatus.FAILED_RETRYABLE,
                errorMessage
        );
    }

    public static PaymentAttempt failedNonRetryable(
            UUID paymentId,
            int attemptNumber,
            String connectorId,
            Instant startedAt,
            String errorMessage
    ) {
        return build(
                paymentId,
                attemptNumber,
                connectorId,
                startedAt,
                AttemptStatus.FAILED_NON_RETRYABLE,
                errorMessage
        );
    }

    private static PaymentAttempt build(
            UUID paymentId,
            int attemptNumber,
            String connectorId,
            Instant startedAt,
            AttemptStatus status,
            String errorMessage
    ) {

        PaymentAttempt attempt = new PaymentAttempt();

        attempt.id = UUID.randomUUID();
        attempt.paymentId = paymentId;
        attempt.attemptNumber = attemptNumber;
        attempt.connectorId = connectorId;
        attempt.status = status;
        attempt.errorMessage = errorMessage;
        attempt.startedAt = startedAt;
        attempt.endedAt = Instant.now();

        return attempt;
    }
}
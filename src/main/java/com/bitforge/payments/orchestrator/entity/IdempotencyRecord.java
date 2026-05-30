package com.bitforge.payments.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(length = 128)
    private String id;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyState state;

    @Column
    private UUID paymentId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean persisted = false;

    public static IdempotencyRecord startNew(
            String key,
            String requestHash,
            Duration ttl
    ) {

        IdempotencyRecord record = new IdempotencyRecord();

        record.id = key;
        record.requestHash = requestHash;
        record.state = IdempotencyState.IN_PROGRESS;
        record.createdAt = Instant.now();
        record.expiresAt = record.createdAt.plus(ttl);

        return record;
    }

    public void complete(UUID paymentId) {
        this.state = IdempotencyState.COMPLETED;
        this.paymentId = paymentId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}
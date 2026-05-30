package com.bitforge.payments.orchestrator.repository;

import com.bitforge.payments.orchestrator.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    long deleteAllByExpiresAtBefore(Instant cutoff);
}
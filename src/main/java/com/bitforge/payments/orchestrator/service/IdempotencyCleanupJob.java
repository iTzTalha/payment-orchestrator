package com.bitforge.payments.orchestrator.service;

import com.bitforge.payments.orchestrator.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private final IdempotencyRecordRepository idempotencyRepository;

    @Scheduled(cron = "0 0 * * * *") // every hour
    @Transactional
    public void purgeExpired() {
        idempotencyRepository.deleteAllByExpiresAtBefore(Instant.now());
    }
}
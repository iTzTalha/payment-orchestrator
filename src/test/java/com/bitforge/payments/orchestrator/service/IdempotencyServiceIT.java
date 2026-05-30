package com.bitforge.payments.orchestrator.service;

import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.dto.PaymentResponse;
import com.bitforge.payments.orchestrator.entity.IdempotencyRecord;
import com.bitforge.payments.orchestrator.entity.IdempotencyState;
import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import com.bitforge.payments.orchestrator.entity.PaymentStatus;
import com.bitforge.payments.orchestrator.exception.IdempotencyKeyConflictException;
import com.bitforge.payments.orchestrator.repository.IdempotencyRecordRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceIT {

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IdempotencyCleanupJob cleanupJob;

    private static final CreatePaymentRequest REQUEST = 
            new CreatePaymentRequest("ord-1", 1000L, "USD", PaymentMethod.CARD);
            
    private static final CreatePaymentRequest DIFFERENT_REQUEST = 
            new CreatePaymentRequest("ord-2", 2000L, "USD", PaymentMethod.CARD);

    @AfterEach
    void cleanup() {
        idempotencyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void newKey_persistsRecord_andReturnsWorkResult() {
        String key = "key-12345678";
        PaymentResponse stubbed = PaymentResponse.from(seedSucceededPayment());

        PaymentResponse result = idempotencyService.executeIdempotent(key, REQUEST, () -> stubbed);

        assertThat(result).isEqualTo(stubbed);

        IdempotencyRecord record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getState()).isEqualTo(IdempotencyState.COMPLETED);
        assertThat(record.getPaymentId()).isEqualTo(stubbed.id());
        assertThat(record.getCreatedAt()).isBefore(record.getExpiresAt());
    }

    @Test
    void sameKey_sameBody_replaysPersistedPayment() {
        String key = "key-replay000";
        PaymentResponse first = PaymentResponse.from(seedSucceededPayment());

        idempotencyService.executeIdempotent(key, REQUEST, () -> first);

        PaymentResponse replay = idempotencyService.executeIdempotent(
                key, REQUEST, () -> { throw new AssertionError("work supplier must not run on replay"); });

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void sameKey_differentBody_throwsConflict_withDiffersMessage() {
        String key = "key-conflict0";
        idempotencyService.executeIdempotent(
                key, REQUEST, () -> PaymentResponse.from(seedSucceededPayment()));

        assertThatThrownBy(() -> idempotencyService.executeIdempotent(
                key, DIFFERENT_REQUEST, () -> { throw new AssertionError(); }))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("differs from the original");
    }

    @Test
    void sameKey_inProgress_throwsConflict_withInProgressMessage() {
        String key = "key-inflight0";
        idempotencyRepository.save(IdempotencyRecord.startNew(
                key, IdempotencyService.canonicalHash(REQUEST), Duration.ofHours(24)));

        assertThatThrownBy(() -> idempotencyService.executeIdempotent(
                key, REQUEST, () -> { throw new AssertionError(); }))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("still in progress");
    }

    @Test
    void workThrows_recordIsDeleted_soKeyIsReusable() {
        String key = "key-rollback0";
        Payment seeded = seedSucceededPayment();
        PaymentResponse later = PaymentResponse.from(seeded);

        assertThatThrownBy(() -> idempotencyService.executeIdempotent(
                key, REQUEST, () -> { throw new RuntimeException("downstream blew up"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(idempotencyRepository.findById(key)).isEmpty();

        PaymentResponse second = idempotencyService.executeIdempotent(key, REQUEST, () -> later);
        assertThat(second).isEqualTo(later);
    }

    @Test
    void expiredCompletedRecord_isEvictedOnReplay_andTreatedAsFreshRequest() {
        String key = "key-stale000";
        // Pre-seed a COMPLETED-but-expired record pointing at an old payment, simulating a row
        // that the cleanup-cron job has not yet swept.
        Payment stalePayment = seedSucceededPayment();
        IdempotencyRecord stale = IdempotencyRecord.startNew(
                key, IdempotencyService.canonicalHash(REQUEST), Duration.ofSeconds(-1));
        stale.complete(stalePayment.getId());
        idempotencyRepository.save(stale);
        assertThat(idempotencyRepository.findById(key).isPresent());

        PaymentResponse fresh = PaymentResponse.from(seedSucceededPayment());

        // Same key + same body, but the existing row is expired: the service must NOT replay the
        // old payment id - it should evict the stale row and let the supplier run as a new request.
        PaymentResponse result = idempotencyService.executeIdempotent(key, REQUEST, () -> fresh);

        assertThat(result.id()).isEqualTo(fresh.id());
        assertThat(result.id()).isNotEqualTo(stalePayment.getId());

        IdempotencyRecord newRecord = idempotencyRepository.findById(key).orElseThrow();
        assertThat(newRecord.getState()).isEqualTo(IdempotencyState.COMPLETED);
        assertThat(newRecord.getPaymentId()).isEqualTo(fresh.id());
        assertThat(newRecord.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void expiredRecord_withDifferentBody_isAlsoEvicted_andNewRequestSucceeds() {
        String key = "key-stale0001";
        // Expired rows are evicted regardless of body - a stale row is logically dead and must
        // not surface as a "differs from original" 409 to the caller.
        Payment stalePayment = seedSucceededPayment();
        IdempotencyRecord stale = IdempotencyRecord.startNew(
                key, IdempotencyService.canonicalHash(REQUEST), Duration.ofSeconds(-1));
        stale.complete(stalePayment.getId());
        idempotencyRepository.save(stale);

        PaymentResponse fresh = PaymentResponse.from(seedSucceededPayment());

        PaymentResponse result = idempotencyService.executeIdempotent(key, DIFFERENT_REQUEST, () -> fresh);

        assertThat(result.id()).isEqualTo(fresh.id());
        IdempotencyRecord newRecord = idempotencyRepository.findById(key).orElseThrow();
        assertThat(newRecord.getRequestHash()).isEqualTo(IdempotencyService.canonicalHash(DIFFERENT_REQUEST));
    }

    @Test
    void expiredInProgressRecord_isEvicted_doesNotThrowInProgressConflict() {
        String key = "key-stale0002";
        // An IN_PROGRESS row past its TTL is a stuck/crashed prior attempt - must be evictable
        // rather than wedging future requests with "still in progress".
        IdempotencyRecord stuck = IdempotencyRecord.startNew(
                key, IdempotencyService.canonicalHash(REQUEST), Duration.ofSeconds(-1));
        idempotencyRepository.save(stuck);
        assertThat(stuck.getState()).isEqualTo(IdempotencyState.IN_PROGRESS);

        PaymentResponse fresh = PaymentResponse.from(seedSucceededPayment());

        PaymentResponse result = idempotencyService.executeIdempotent(key, REQUEST, () -> fresh);

        assertThat(result.id()).isEqualTo(fresh.id());
        assertThat(idempotencyRepository.findById(key).orElseThrow().getState()).isEqualTo(IdempotencyState.COMPLETED);
    }

    @Test
    void cleanupJob_removesExpiredRecords_andLeavesLiveOnesIntact() {
        IdempotencyRecord live = idempotencyRepository.save(
                IdempotencyRecord.startNew("key-live00000", "hash-live", Duration.ofHours(1)));
        IdempotencyRecord expired = idempotencyRepository.save(
                IdempotencyRecord.startNew("key-expired00", "hash-expired", Duration.ofSeconds(-1)));

        assertThat(idempotencyRepository.findAll()).hasSize(2);
        assertThat(expired.getExpiresAt()).isBefore(Instant.now());

        cleanupJob.purgeExpired();

        assertThat(idempotencyRepository.findById(live.getId())).isPresent();
        assertThat(idempotencyRepository.findById(expired.getId())).isEmpty();
    }

    private Payment seedSucceededPayment() {
        Payment p = Payment.create("ord-seed", 1000L, "USD", PaymentMethod.CARD);
        p.transitionTo(PaymentStatus.PROCESSING);
        p.transitionTo(PaymentStatus.SUCCEEDED);
        return paymentRepository.save(p);
    }
}

package com.bitforge.payments.orchestrator.service;

import com.bitforge.payments.orchestrator.dto.PaymentResponse;
import com.bitforge.payments.orchestrator.entity.IdempotencyRecord;
import com.bitforge.payments.orchestrator.entity.IdempotencyState;
import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.exception.IdempotencyKeyConflictException;
import com.bitforge.payments.orchestrator.exception.PaymentNotFoundException;
import com.bitforge.payments.orchestrator.repository.IdempotencyRecordRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Enforces at-most-once processing for a given {@code Idempotency-Key} on POST /payments.
 */
@Service
public class IdempotencyService {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentRepository paymentRepository;
    private final TransactionTemplate requiresNew;

    @Value("${orchestrator.idempotency.ttl:PT24H}")
    private Duration ttl;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRepository, PaymentRepository paymentRepository, PlatformTransactionManager transactionManager) {

        this.idempotencyRepository = idempotencyRepository;
        this.paymentRepository = paymentRepository;

        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    static String canonicalHash(Object body) {

        try {

            byte[] bytes = CANONICAL_MAPPER.writeValueAsBytes(body);

            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException("SHA-256 unavailable", e);

        } catch (JsonProcessingException e) {

            throw new IllegalStateException("Failed to hash request body", e);
        }
    }

    public PaymentResponse executeIdempotent(String key, Object requestBody, Supplier<PaymentResponse> work) {
        String requestHash = canonicalHash(requestBody);

        while (true) {
            try {
                requiresNew.executeWithoutResult(status -> idempotencyRepository.save(IdempotencyRecord.startNew(key, requestHash, ttl)));
                break;
            } catch (DataIntegrityViolationException existing) {
                try {
                    return handleConflict(key, requestHash);
                } catch (ExpiredIdempotencyRecordException retry) {
                    // expired row was evicted by handleConflict; loop to retry the insert
                }
            }
        }

        try {
            PaymentResponse response = work.get();
            // Mutation flushed by JPA dirty-checking at commit of this REQUIRES_NEW transaction.
            requiresNew.executeWithoutResult(status -> {
                IdempotencyRecord row = idempotencyRepository.findById(key).orElseThrow();
                row.complete(response.id());
            });
            return response;
        } catch (RuntimeException ex) {
            requiresNew.executeWithoutResult(status -> idempotencyRepository.deleteById(key));
            throw ex;
        }
    }

    private PaymentResponse handleConflict(String key, String requestHash) {
        IdempotencyRecord existing = idempotencyRepository.findById(key).orElseThrow(() -> new IdempotencyKeyConflictException(key, "record vanished mid-flight"));

        // Expired records are logically gone – the cleanup job just
        // hasn't swept them yet. Drop the row in its own transaction
        // and let the caller retry the insert as a fresh request,
        // closing the race between TTL expiry and the cleanup cron interval.
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            requiresNew.executeWithoutResult(status -> idempotencyRepository.deleteById(key));
            throw new ExpiredIdempotencyRecordException();
        }

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(key, "request body differs from the original");
        }

        if (existing.getState() == IdempotencyState.IN_PROGRESS) {
            throw new IdempotencyKeyConflictException(key, "original request is still in progress");
        }

        Payment payment = paymentRepository.findById(existing.getPaymentId()).orElseThrow(() -> new PaymentNotFoundException(existing.getPaymentId()));

        return PaymentResponse.from(payment);
    }

    /**
     * Internal signal: the conflicting record had expired and was deleted;
     * the caller should retry.
     */
    private static final class ExpiredIdempotencyRecordException extends RuntimeException {
    }
}
package com.bitforge.payments.orchestrator.service;

import com.bitforge.payments.orchestrator.dto.CreatePaymentRequest;
import com.bitforge.payments.orchestrator.dto.PaymentAttemptResponse;
import com.bitforge.payments.orchestrator.dto.PaymentResponse;
import com.bitforge.payments.orchestrator.entity.Payment;
import com.bitforge.payments.orchestrator.exception.PaymentNotFoundException;
import com.bitforge.payments.orchestrator.repository.PaymentAttemptRepository;
import com.bitforge.payments.orchestrator.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentProcessor paymentProcessor;

    @Transactional
    public PaymentResponse create(CreatePaymentRequest request) {

        Payment payment = Payment.create(
                request.merchantReference(),
                request.amountMinor(),
                request.currency(),
                request.paymentMethod()
        );

        paymentRepository.save(payment);

        paymentProcessor.process(payment);

        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(UUID id) {

        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<PaymentAttemptResponse> findAttempts(UUID paymentId) {

        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }

        return paymentAttemptRepository
                .findByPaymentIdOrderByAttemptNumberAsc(paymentId)
                .stream()
                .map(PaymentAttemptResponse::from)
                .toList();
    }
}
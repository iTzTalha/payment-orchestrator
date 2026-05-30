package com.bitforge.payments.orchestrator.entity;

import com.bitforge.payments.orchestrator.exception.InvalidStatusTransitionException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String merchantReference;

    @Column(nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    public static Payment create(
            String merchantReference,
            long amountMinor,
            String currency,
            PaymentMethod paymentMethod
    ) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.merchantReference = merchantReference;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.paymentMethod = paymentMethod;
        payment.status = PaymentStatus.INITIATED;
        return payment;
    }

    public void transitionTo(PaymentStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(this.status, next);
        }

        this.status = next;
    }
}
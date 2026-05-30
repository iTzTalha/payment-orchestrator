package com.bitforge.payments.orchestrator.repository;

import com.bitforge.payments.orchestrator.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
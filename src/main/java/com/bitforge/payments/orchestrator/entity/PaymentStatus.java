package com.bitforge.payments.orchestrator.entity;

public enum PaymentStatus {

    INITIATED,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case INITIATED -> next == PROCESSING;
            case PROCESSING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
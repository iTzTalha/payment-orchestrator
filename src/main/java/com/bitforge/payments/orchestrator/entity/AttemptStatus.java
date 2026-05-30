package com.bitforge.payments.orchestrator.entity;

public enum AttemptStatus {
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_NON_RETRYABLE
}
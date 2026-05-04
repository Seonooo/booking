package com.booking.domain.idempotency;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * IdempotencyKey Aggregate의 상태 (ADR-006).
 */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED
}

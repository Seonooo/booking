package com.booking.application;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * 멱등성 체크 3-state 결과 (ADR-006).
 * NEW → 처리 계속 / PROCESSING → 409 / COMPLETED → 200 cached / HASH_MISMATCH → 422
 */
public record IdempotencyCheckResult(
        ResultType type,
        String cachedResponse
) {
    public enum ResultType {
        NEW,
        PROCESSING,
        COMPLETED,
        HASH_MISMATCH
    }
}

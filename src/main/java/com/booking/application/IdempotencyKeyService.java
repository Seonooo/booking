package com.booking.application;

import com.booking.domain.idempotency.IdempotencyKeyRepository;

import java.util.UUID;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * 멱등성 체크 + SETNX (Redis Lua atomic, ADR-002) + DB UNIQUE 이중 계층 (ADR-006).
 */
public class IdempotencyKeyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyKeyService(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Redis 1차 Lua atomic SETNX → 3-state 분기 (ADR-006 §흐름).
     * Redis 장애 시 Fail-Closed → 503 (ADR-007).
     * Phase 3에서 본격 구현.
     */
    public IdempotencyCheckResult checkAndReserve(UUID idempotencyKey, String bodyHash) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }

    /**
     * 결제 완료 후 Redis COMPLETED 갱신 + DB UPDATE.
     * Phase 3에서 본격 구현.
     */
    public void complete(UUID idempotencyKey, String bodyHash, String responsePayload, long bookingId) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }
}

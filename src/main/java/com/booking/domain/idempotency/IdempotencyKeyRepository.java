package com.booking.domain.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * Driven port interface (ADR-014): 외부 기술 import 0.
 */
public interface IdempotencyKeyRepository {

    Optional<IdempotencyKey> findById(UUID idempotencyKey);

    void save(IdempotencyKey idempotencyKey);

    void updateToCompleted(UUID idempotencyKey, String responsePayload, long bookingId);
}

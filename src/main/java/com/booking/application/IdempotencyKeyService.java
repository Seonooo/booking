package com.booking.application;

import com.booking.application.IdempotencyCheckResult.ResultType;
import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 멱등성 체크 + 3-state 분기 application service (ADR-006).
 *
 * <p>본 phase (3.2) 는 Repository (DB 영속) 단일 계층만 다룬다. Redis Lua 1차
 * 캐시 (ADR-002 atomic SETNX, ADR-007 Fail-Closed) 는 Phase 3.3 에서
 * {@code IdempotencyLuaScript} 가 추가될 때 통합한다.
 */
public class IdempotencyKeyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyKeyService(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Repository 조회 결과를 3-state 결과로 분기 (ADR-006 §흐름).
     *
     * <p>분기 우선순위:
     * <ol>
     *   <li>존재하지 않음 → {@link ResultType#NEW}</li>
     *   <li>존재하지만 expired → {@link ResultType#NEW} (만료 키는 새 결제로 처리)</li>
     *   <li>body_hash 불일치 → {@link ResultType#HASH_MISMATCH} (변조 의심)</li>
     *   <li>PROCESSING → {@link ResultType#PROCESSING}</li>
     *   <li>COMPLETED → {@link ResultType#COMPLETED} + cachedResponse</li>
     * </ol>
     */
    public IdempotencyCheckResult checkAndReserve(UUID idempotencyKey, String bodyHash) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findById(idempotencyKey);

        if (existing.isEmpty()) {
            return new IdempotencyCheckResult(ResultType.NEW, null);
        }

        IdempotencyKey key = existing.get();

        if (key.isExpired(Instant.now())) {
            return new IdempotencyCheckResult(ResultType.NEW, null);
        }

        if (!key.isBodyHashMatching(bodyHash)) {
            return new IdempotencyCheckResult(ResultType.HASH_MISMATCH, null);
        }

        if (key.isProcessing()) {
            return new IdempotencyCheckResult(ResultType.PROCESSING, null);
        }

        if (key.isCompleted()) {
            return new IdempotencyCheckResult(ResultType.COMPLETED, key.getResponsePayload());
        }

        // 도달 불가 — IdempotencyStatus enum 모든 값을 위에서 처리
        throw new IllegalStateException("Unhandled IdempotencyStatus: " + key.getStatus());
    }

    /**
     * 결제 완료 후 DB UPDATE (PROCESSING → COMPLETED + responsePayload + bookingId).
     *
     * <p>Redis 캐시 갱신은 Phase 3.3 에서 {@code IdempotencyLuaScript.markCompleted}
     * 가 추가되면 본 메소드 직후에 호출한다 (ADR-006 §흐름 — DB 트랜잭션 commit 후 Redis 갱신).
     *
     * @param bodyHash 본격 Redis 갱신 시 storedHash 일치 검증용으로 사용 예정
     */
    public void complete(UUID idempotencyKey, String bodyHash, String responsePayload, long bookingId) {
        idempotencyKeyRepository.updateToCompleted(idempotencyKey, responsePayload, bookingId);
    }
}

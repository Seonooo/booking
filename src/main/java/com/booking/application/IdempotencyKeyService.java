package com.booking.application;

import com.booking.application.IdempotencyCheckResult.ResultType;
import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import com.booking.infrastructure.redis.IdempotencyLuaScript;
import com.booking.infrastructure.redis.RedisUnavailableException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 멱등성 체크 application service (ADR-006).
 *
 * <p>흐름 (ADR-006 §흐름 / ADR-007 Fail-Closed):
 * <ol>
 *   <li>Redis 1차 — {@link IdempotencyLuaScript} atomic SETNX (ADR-002).</li>
 *   <li>Redis 장애 시 ({@link RedisUnavailableException}) — DB 2차 방어선
 *       ({@link IdempotencyKeyRepository}) 으로 fallback. 정확한 hash 검증으로
 *       이중 결제 차단을 보장.</li>
 * </ol>
 */
public class IdempotencyKeyService {

    private final IdempotencyLuaScript luaScript;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyKeyService(IdempotencyLuaScript luaScript,
                                 IdempotencyKeyRepository idempotencyKeyRepository) {
        this.luaScript = luaScript;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    public IdempotencyCheckResult checkAndReserve(UUID idempotencyKey, String bodyHash) {
        try {
            return luaScript.execute(idempotencyKey, bodyHash);
        } catch (RedisUnavailableException e) {
            // ADR-006 §흐름 — Redis 장애 시 DB 2차 방어선
            return checkInDatabase(idempotencyKey, bodyHash);
        }
    }

    /**
     * DB 2차 방어선 — Redis 장애 시 fallback. 분기 우선순위:
     * <ol>
     *   <li>존재하지 않음 → {@link ResultType#NEW}</li>
     *   <li>existing.isExpired(now) → {@link ResultType#NEW}</li>
     *   <li>!isBodyHashMatching → {@link ResultType#HASH_MISMATCH}</li>
     *   <li>isProcessing → {@link ResultType#PROCESSING}</li>
     *   <li>isCompleted → {@link ResultType#COMPLETED} + cachedResponse</li>
     * </ol>
     */
    private IdempotencyCheckResult checkInDatabase(UUID idempotencyKey, String bodyHash) {
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

        throw new IllegalStateException("Unhandled IdempotencyStatus: " + key.getStatus());
    }

    /**
     * 결제 완료 후 DB UPDATE + Redis 캐시 갱신 (ADR-006 §흐름).
     *
     * <p>호출 순서:
     * <ol>
     *   <li>DB UPDATE — source of truth 1차. 호출자의 {@code @Transactional} 컨텍스트 안에서 실행.</li>
     *   <li>Redis markCompleted — Lua atomic 으로 PROCESSING → COMPLETED + responsePayload 캐시.
     *       호출자의 트랜잭션 commit 후 실행되도록 호출자가 보장. Redis 실패는 warn log 만 (DB 가 source of truth).</li>
     * </ol>
     */
    public void complete(UUID idempotencyKey, String bodyHash, String responsePayload, long bookingId) {
        idempotencyKeyRepository.updateToCompleted(idempotencyKey, responsePayload, bookingId);
        luaScript.markCompleted(idempotencyKey, bodyHash, responsePayload);
    }
}

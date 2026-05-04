package com.booking.domain.idempotency;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * IdempotencyKey Aggregate Root (ADR-006, ERD §4.6).
 * domain layer: 외부 기술 의존 0 (ADR-014).
 */
public class IdempotencyKey {

    private final UUID idempotencyKey;
    private final long userId;
    private final String bodyHash;
    private IdempotencyStatus status;
    private String responsePayload;
    private Long bookingId;
    private final Instant createdAt;
    private final Instant expiresAt;

    public IdempotencyKey(UUID idempotencyKey, long userId, String bodyHash,
                          IdempotencyStatus status, String responsePayload,
                          Long bookingId, Instant createdAt, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.bodyHash = bodyHash;
        this.status = status;
        this.responsePayload = responsePayload;
        this.bookingId = bookingId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getIdempotencyKey() { return idempotencyKey; }
    public long getUserId() { return userId; }
    public String getBodyHash() { return bodyHash; }
    public IdempotencyStatus getStatus() { return status; }
    public String getResponsePayload() { return responsePayload; }
    public Long getBookingId() { return bookingId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    /** Phase 3에서 본격 구현. */
    public boolean isExpired(Instant now) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }

    /** Phase 3에서 본격 구현. */
    public boolean isBodyHashMatching(String incomingHash) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }

    /** Phase 3에서 본격 구현. */
    public boolean isProcessing() {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }

    /** Phase 3에서 본격 구현. */
    public boolean isCompleted() {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }

    /** PROCESSING → COMPLETED, 새 인스턴스 반환. Phase 3에서 본격 구현. */
    public IdempotencyKey complete(String responsePayload) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }
}

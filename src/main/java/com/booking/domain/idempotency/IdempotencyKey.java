package com.booking.domain.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * IdempotencyKey Aggregate Root (ADR-006, ERD §4.6).
 *
 * <p>Domain layer — 외부 기술 의존 0 (ADR-014). 모든 필드 final, 상태 전이는
 * {@link #complete(String, long)} 가 새 인스턴스를 반환한다 (immutable aggregate).
 */
public class IdempotencyKey {

    private final UUID idempotencyKey;
    private final long userId;
    private final String bodyHash;
    private final IdempotencyStatus status;
    private final String responsePayload;
    private final Long bookingId;
    private final Instant createdAt;
    private final Instant expiresAt;

    public IdempotencyKey(UUID idempotencyKey, long userId, String bodyHash,
                          IdempotencyStatus status, String responsePayload,
                          Long bookingId, Instant createdAt, Instant expiresAt) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.bodyHash = Objects.requireNonNull(bodyHash, "bodyHash");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.userId = userId;
        this.responsePayload = responsePayload;
        this.bookingId = bookingId;
    }

    public UUID getIdempotencyKey() { return idempotencyKey; }
    public long getUserId() { return userId; }
    public String getBodyHash() { return bodyHash; }
    public IdempotencyStatus getStatus() { return status; }
    public String getResponsePayload() { return responsePayload; }
    public Long getBookingId() { return bookingId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return now.isAfter(expiresAt);
    }

    public boolean isBodyHashMatching(String incomingHash) {
        return bodyHash.equals(incomingHash);
    }

    public boolean isProcessing() {
        return status == IdempotencyStatus.PROCESSING;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    /**
     * PROCESSING → COMPLETED 전이. 새 인스턴스를 반환한다.
     *
     * @throws IllegalStateException 이미 COMPLETED 상태인 경우
     */
    public IdempotencyKey complete(String responsePayload, long bookingId) {
        if (status != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException(
                "complete() requires PROCESSING status, current=" + status);
        }
        Objects.requireNonNull(responsePayload, "responsePayload");
        return new IdempotencyKey(
            idempotencyKey, userId, bodyHash,
            IdempotencyStatus.COMPLETED, responsePayload, bookingId,
            createdAt, expiresAt);
    }
}

package com.booking.infrastructure.persistence;

import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code idempotency_key} table (ERD §4.6).
 *
 * <p>Infrastructure layer — domain {@link IdempotencyKey} 와 {@link #toDomain()} /
 * {@link #fromDomain(IdempotencyKey)} 으로 양방향 변환. domain layer 는 본 클래스에
 * 의존하지 않음 (ADR-014).
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyJpaEntity {

    @Id
    @Column(name = "idempotency_key", columnDefinition = "BINARY(16)", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "body_hash", length = 64, nullable = false)
    private String bodyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private IdempotencyStatus status;

    @Column(name = "response_payload", columnDefinition = "JSON")
    private String responsePayload;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyJpaEntity() {
        // JPA spec — no-arg constructor
    }

    private IdempotencyKeyJpaEntity(UUID idempotencyKey, long userId, String bodyHash,
                                    IdempotencyStatus status, String responsePayload, Long bookingId,
                                    Instant createdAt, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.bodyHash = bodyHash;
        this.status = status;
        this.responsePayload = responsePayload;
        this.bookingId = bookingId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static IdempotencyKeyJpaEntity fromDomain(IdempotencyKey key) {
        return new IdempotencyKeyJpaEntity(
            key.getIdempotencyKey(),
            key.getUserId(),
            key.getBodyHash(),
            key.getStatus(),
            key.getResponsePayload(),
            key.getBookingId(),
            key.getCreatedAt(),
            key.getExpiresAt()
        );
    }

    public IdempotencyKey toDomain() {
        return new IdempotencyKey(
            idempotencyKey, userId, bodyHash, status,
            responsePayload, bookingId, createdAt, expiresAt
        );
    }

    public UUID getIdempotencyKey() { return idempotencyKey; }
}

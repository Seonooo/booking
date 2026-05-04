package com.booking.domain.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * OutboxEvent aggregate (ERD §4.4 / ADR-010).
 *
 * <p>booking save 트랜잭션 안에서 PENDING INSERT — atomicity 보장. PUBLISHED 전이는
 * Outbox 폴러 (feature-005). 컨슈머 멱등 처리는 ADR-010 amendment §write-first 패턴
 * (feature-005).
 *
 * <p>Domain layer — 외부 기술 의존 0. minimal — getter only.
 */
public class OutboxEvent {

    private final Long id;
    private final String eventType;
    private final UUID idempotencyKey;
    private final String payload;
    private final OutboxEventStatus status;
    private final Instant createdAt;
    private final Instant publishedAt;  // nullable

    public OutboxEvent(Long id, String eventType, UUID idempotencyKey, String payload,
                       OutboxEventStatus status, Instant createdAt, Instant publishedAt) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.id = id;
        this.publishedAt = publishedAt;
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}

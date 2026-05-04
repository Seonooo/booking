package com.booking.infrastructure.persistence;

import com.booking.domain.outbox.OutboxEvent;
import com.booking.domain.outbox.OutboxEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code outbox_event} table (ERD §4.4).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "idempotency_key", columnDefinition = "BINARY(16)", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "payload", columnDefinition = "JSON", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventJpaEntity() {
        // JPA spec — no-arg constructor
    }

    private OutboxEventJpaEntity(Long id, String eventType, UUID idempotencyKey, String payload,
                                 OutboxEventStatus status, Instant createdAt, Instant publishedAt) {
        this.id = id;
        this.eventType = eventType;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
    }

    public static OutboxEventJpaEntity fromDomain(OutboxEvent e) {
        return new OutboxEventJpaEntity(
            e.getId(), e.getEventType(), e.getIdempotencyKey(), e.getPayload(),
            e.getStatus(), e.getCreatedAt(), e.getPublishedAt()
        );
    }

    public OutboxEvent toDomain() {
        return new OutboxEvent(id, eventType, idempotencyKey, payload, status, createdAt, publishedAt);
    }

    public Long getId() { return id; }
}

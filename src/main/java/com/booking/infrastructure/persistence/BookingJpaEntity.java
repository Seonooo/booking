package com.booking.infrastructure.persistence;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code booking} table (ERD §4.1).
 *
 * <p>Infrastructure layer — domain {@link Booking} 와 {@link #toDomain()} /
 * {@link #fromDomain(Booking)} 으로 양방향 변환. domain layer 는 본 클래스에
 * 의존하지 않음 (ADR-014).
 */
@Entity
@Table(name = "booking")
public class BookingJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "accommodation_id", nullable = false)
    private long accommodationId;

    @Column(name = "idempotency_key", columnDefinition = "BINARY(16)", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_composition_snapshot", columnDefinition = "JSON", nullable = false)
    private String paymentCompositionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BookingJpaEntity() {
        // JPA spec — no-arg constructor
    }

    private BookingJpaEntity(Long id, UUID idempotencyKey, long userId, long accommodationId,
                             BigDecimal amount, String paymentCompositionSnapshot,
                             BookingStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.accommodationId = accommodationId;
        this.amount = amount;
        this.paymentCompositionSnapshot = paymentCompositionSnapshot;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static BookingJpaEntity fromDomain(Booking b) {
        return new BookingJpaEntity(
            b.getId(),
            b.getIdempotencyKey(),
            b.getUserId(),
            b.getAccommodationId(),
            b.getAmount(),
            b.getPaymentCompositionSnapshot(),
            b.getStatus(),
            b.getCreatedAt(),
            b.getUpdatedAt()
        );
    }

    public Booking toDomain() {
        return new Booking(
            id, idempotencyKey, userId, accommodationId, amount,
            paymentCompositionSnapshot, status, createdAt, updatedAt
        );
    }

    public Long getId() { return id; }
}

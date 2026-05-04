package com.booking.infrastructure.persistence;

import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
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
 * JPA mapping for {@code payment_attempt} table (ERD §4.2).
 *
 * <p>본 PR 매핑 외 컬럼 ({@code last_reconcile_at}, {@code reconcile_retry_count}) 은
 * Reconciliation feature 에서 활성. DB default (0 / null) 적용으로 본 PR INSERT 무관.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttemptJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "attempt_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID attemptId;

    @Column(name = "booking_id", nullable = false)
    private long bookingId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_composition_snapshot", columnDefinition = "JSON", nullable = false)
    private String paymentCompositionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PaymentAttemptStatus status;

    @Column(name = "external_payment_id", length = 100)
    private String externalPaymentId;

    @Column(name = "last_requested_at")
    private Instant lastRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttemptJpaEntity() {
        // JPA spec — no-arg constructor
    }

    private PaymentAttemptJpaEntity(Long id, UUID attemptId, long bookingId, BigDecimal amount,
                                    String paymentCompositionSnapshot, PaymentAttemptStatus status,
                                    String externalPaymentId, Instant lastRequestedAt,
                                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.attemptId = attemptId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.paymentCompositionSnapshot = paymentCompositionSnapshot;
        this.status = status;
        this.externalPaymentId = externalPaymentId;
        this.lastRequestedAt = lastRequestedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PaymentAttemptJpaEntity fromDomain(PaymentAttempt p) {
        return new PaymentAttemptJpaEntity(
            p.getId(), p.getAttemptId(), p.getBookingId(), p.getAmount(),
            p.getPaymentCompositionSnapshot(), p.getStatus(),
            p.getExternalPaymentId(), p.getLastRequestedAt(),
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }

    public PaymentAttempt toDomain() {
        return new PaymentAttempt(
            id, attemptId, bookingId, amount, paymentCompositionSnapshot,
            status, externalPaymentId, lastRequestedAt, createdAt, updatedAt
        );
    }

    public Long getId() { return id; }
}

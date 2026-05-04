package com.booking.domain.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Booking Aggregate Root (ERD §4.1).
 *
 * <p>Domain layer — 외부 기술 의존 0 (ADR-014). 본 PR Phase 3.4 에서는 {@link
 * BookingStatus#COMPLETED} 직접 생성만 활성 (재고 / Saga / state 전이는 future
 * feature). state-transition 메소드는 의도적으로 제외 — 본 feature 가 검증할
 * 시나리오는 *"row 1건 INSERT"* 만이라 minimal.
 */
public class Booking {

    private final Long id;
    private final UUID idempotencyKey;
    private final long userId;
    private final long accommodationId;
    private final BigDecimal amount;
    private final String paymentCompositionSnapshot;
    private final BookingStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Booking(Long id, UUID idempotencyKey, long userId, long accommodationId,
                   BigDecimal amount, String paymentCompositionSnapshot,
                   BookingStatus status, Instant createdAt, Instant updatedAt) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paymentCompositionSnapshot = Objects.requireNonNull(paymentCompositionSnapshot, "paymentCompositionSnapshot");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.id = id;
        this.userId = userId;
        this.accommodationId = accommodationId;
    }

    public Long getId() { return id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public long getUserId() { return userId; }
    public long getAccommodationId() { return accommodationId; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentCompositionSnapshot() { return paymentCompositionSnapshot; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

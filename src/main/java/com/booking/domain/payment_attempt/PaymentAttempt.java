package com.booking.domain.payment_attempt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PaymentAttempt aggregate (ERD §4.2 / ADR-011).
 *
 * <p>booking 1건당 여러 attempt 가능 — 본 PR scope 는 booking 1건당 attempt 1개
 * (카드 변경 = 새 booking + 새 attempt 가정 — REQUIREMENTS §1.2 정합).
 *
 * <p>Domain layer — 외부 기술 의존 0 (ADR-014). minimal — getter only, 상태 전이 메소드
 * 미포함 (호출자가 새 instance 생성 또는 repository CAS 메소드 호출).
 */
public class PaymentAttempt {

    private final Long id;
    private final UUID attemptId;
    private final long bookingId;
    private final BigDecimal amount;
    private final String paymentCompositionSnapshot;
    private final PaymentAttemptStatus status;
    private final String externalPaymentId;  // nullable — ACKED 이후 set
    private final Instant lastRequestedAt;    // nullable — REQUESTED 이후 set
    private final Instant createdAt;
    private final Instant updatedAt;

    public PaymentAttempt(Long id, UUID attemptId, long bookingId,
                          BigDecimal amount, String paymentCompositionSnapshot,
                          PaymentAttemptStatus status, String externalPaymentId,
                          Instant lastRequestedAt, Instant createdAt, Instant updatedAt) {
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paymentCompositionSnapshot = Objects.requireNonNull(paymentCompositionSnapshot, "paymentCompositionSnapshot");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.id = id;
        this.bookingId = bookingId;
        this.externalPaymentId = externalPaymentId;
        this.lastRequestedAt = lastRequestedAt;
    }

    public Long getId() { return id; }
    public UUID getAttemptId() { return attemptId; }
    public long getBookingId() { return bookingId; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentCompositionSnapshot() { return paymentCompositionSnapshot; }
    public PaymentAttemptStatus getStatus() { return status; }
    public String getExternalPaymentId() { return externalPaymentId; }
    public Instant getLastRequestedAt() { return lastRequestedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

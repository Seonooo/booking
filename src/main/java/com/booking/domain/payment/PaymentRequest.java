package com.booking.domain.payment;

import java.math.BigDecimal;

/**
 * PG 호출 입력 (ADR-009 §처리 흐름 단계 5).
 *
 * <p>{@code idempotencyKey} 는 booking 의 멱등성 키와 동일 값 — PG idempotency
 * 와의 통합은 ADR-011 reconciliation 영역. 본 PR 에서는 단순 전달만.
 */
public record PaymentRequest(
        BigDecimal amount,
        String idempotencyKey,
        long userId
) {
}

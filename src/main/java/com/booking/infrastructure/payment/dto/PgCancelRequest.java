package com.booking.infrastructure.payment.dto;

/**
 * PG 취소 요청 본문 — type-safe (Map → record). Saga 보상 (ADR-009).
 */
public record PgCancelRequest(
        String externalPaymentId,
        long cancelAmount
) {
}

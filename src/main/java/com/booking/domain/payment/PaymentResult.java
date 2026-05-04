package com.booking.domain.payment;

/**
 * PG 응답 (ADR-009 §처리 흐름 단계 5).
 *
 * @param externalPaymentId PG 가 부여한 결제 식별자 — Saga 보상 / reconciliation
 *                          시 cancel(paymentKey, ...) 의 인자로 재사용
 * @param status            "SUCCESS" 등 PG 응답 상태 — 도메인 enum 으로의 매핑은
 *                          후속 feature (PaymentAttempt) 에서 본격화
 */
public record PaymentResult(
        String externalPaymentId,
        String status
) {
}

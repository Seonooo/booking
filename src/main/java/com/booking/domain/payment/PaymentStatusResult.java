package com.booking.domain.payment;

/**
 * PG 상태 조회 결과 (ADR-011 §결정 2 / DECISIONS.md §11 케이스 2).
 *
 * <p>Reconciliation worker (feature-007) 가 booking UNKNOWN 상태 row 의 결과 확정 시 사용.
 *
 * <p>NOT_FOUND ≠ FAILED — PG eventual consistency 가정 (ADR-011 §핵심 원칙).
 */
public record PaymentStatusResult(Status status, String externalPaymentId) {

    public enum Status {
        SUCCESS,
        FAILED,
        NOT_FOUND
    }
}

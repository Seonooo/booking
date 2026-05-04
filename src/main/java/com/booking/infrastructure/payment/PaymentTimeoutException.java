package com.booking.infrastructure.payment;

/**
 * PG 5XX 또는 응답 timeout — DECISIONS.md §11 케이스 2 (UNKNOWN 영역).
 *
 * <p>application 레이어가 catch → booking UNKNOWN 마킹 + 503 응답 (Reconciliation worker
 * 가 후속 결과 확정 — feature-007).
 */
public class PaymentTimeoutException extends RuntimeException {

    public PaymentTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

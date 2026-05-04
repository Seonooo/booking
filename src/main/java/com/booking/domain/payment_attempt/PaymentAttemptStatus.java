package com.booking.domain.payment_attempt;

/**
 * PaymentAttempt 상태 머신 (ERD §6.2).
 *
 * <p>본 PR 활성: {@code INIT} / {@code REQUESTED} / {@code SUCCESS} / {@code FAILED} /
 * {@code TIMEOUT}. ADR-011 의 {@code ACKED} 단계는 비동기 콜백 가정인데 본 시스템은
 * 동기 HTTP — REQUESTED → SUCCESS/FAILED 직접 전이.
 */
public enum PaymentAttemptStatus {
    INIT,
    REQUESTED,
    SUCCESS,
    FAILED,
    TIMEOUT
}

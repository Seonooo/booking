package com.booking.infrastructure.payment;

/**
 * PG API 호출 실패 (네트워크 / 거절 / timeout 통합 예외).
 *
 * <p>본 PR Phase 3.4 에서는 단순 500 으로 매핑 — 세부 분류 (PG 거절 400 vs PG
 * timeout 처리 흐름) 는 ADR-009 §결제 실패 분류와 ADR-011 reconciliation 통합
 * 시 본격화한다.
 */
public class PgCallFailedException extends RuntimeException {

    public PgCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

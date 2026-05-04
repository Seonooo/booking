package com.booking.infrastructure.payment;

/**
 * PG cancel API 호출 실패 — Saga 보상 호출 실패 (network / 5XX 등).
 *
 * <p>Outbox 폴러가 후속 재시도 보장 (ADR-010 §결정 — 보상 payload 재발송).
 */
public class PgCancelFailedException extends RuntimeException {

    public PgCancelFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

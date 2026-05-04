package com.booking.infrastructure.payment;

/**
 * PG 4XX 거절 (한도 초과 / 카드 정지 / 인증 실패) — DECISIONS.md §11 케이스 1.
 *
 * <p>application 레이어가 catch → booking FAILED 마킹 + 400 응답 (GlobalExceptionHandler).
 * ADR-008 amendment 정합 — hold 유지 (TTL sweeper feature-006 가 후속 INCR).
 */
public class PaymentRejectedException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public PaymentRejectedException(int statusCode, String responseBody, Throwable cause) {
        super("PG rejected: status=" + statusCode + " body=" + responseBody, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
}

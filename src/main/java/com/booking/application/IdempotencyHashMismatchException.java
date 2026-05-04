package com.booking.application;

/**
 * 같은 멱등성 키 + 다른 body — GlobalExceptionHandler 가 HTTP 422 로 변환
 * (ADR-006 — 변조 의심 시그널).
 */
public class IdempotencyHashMismatchException extends RuntimeException {

    public IdempotencyHashMismatchException() {
        super("데이터 충돌, 키 재발급 필요");
    }
}

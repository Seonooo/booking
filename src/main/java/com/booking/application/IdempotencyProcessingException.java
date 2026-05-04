package com.booking.application;

/**
 * 멱등성 키 처리 중 — GlobalExceptionHandler 가 HTTP 409 로 변환 (ADR-006).
 */
public class IdempotencyProcessingException extends RuntimeException {

    public IdempotencyProcessingException() {
        super("처리 중, 잠시 후 재시도");
    }
}

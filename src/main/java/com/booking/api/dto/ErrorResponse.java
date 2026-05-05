package com.booking.api.dto;

/**
 * HTTP 에러 응답 DTO — type-safe 응답 본문 + OpenAPI 문서화.
 *
 * <p>이전에는 {@code Map.of("message", ...)} 로 응답 — short-lived 직렬화로 정합이지만
 * type-safe + Swagger/OpenAPI 자동 문서화 측면에서 record 가 우월. 본 record 가
 * {@code GlobalExceptionHandler} 의 모든 응답 body 의 단일 type.
 *
 * <p>후속 확장 가능 — `errorCode` (String) / `path` (String) 등 필드 추가 시 본 record 만 수정.
 */
public record ErrorResponse(String message) {
}

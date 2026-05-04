package com.booking.infrastructure.payment.dto;

import java.math.BigDecimal;

/**
 * PG 결제 요청 본문 — type-safe (Map → record).
 *
 * <p>{@code amount} 는 {@link BigDecimal#toPlainString()} 결과 문자열 — Jackson 의 BigDecimal
 * 직렬화는 trailing zeroes 정리 ({@code 45000.00} → {@code 45000.0}) 라 *PG 측 표기 안정성*
 * + *기존 Map.put 동작 정합* 위해 String. 실제 PG SDK 통합 시 SDK 의 request DTO 로 대체.
 */
public record PgPaymentRequest(
        String amount,
        String idempotencyKey,
        long userId
) {

    public static PgPaymentRequest of(BigDecimal amount, String idempotencyKey, long userId) {
        return new PgPaymentRequest(amount.toPlainString(), idempotencyKey, userId);
    }
}

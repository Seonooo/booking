package com.booking.api.booking.dto;

import java.math.BigDecimal;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * POST /booking 요청 DTO. @Valid Bean Validation은 Phase 3.4에서 추가.
 * body_hash 필드: userId|productId|amount.toPlainString()|paymentMethod.toUpperCase()|points (ADR-006 §5).
 */
public record CreateBookingRequest(
        long userId,
        long productId,
        BigDecimal amount,
        String paymentMethod,
        long points
) {
}

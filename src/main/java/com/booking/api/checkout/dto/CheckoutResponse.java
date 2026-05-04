package com.booking.api.checkout.dto;

import java.math.BigDecimal;

/**
 * GET /checkout 응답 (REQUIREMENTS §1.1).
 *
 * <p>본 PR minimal MVP — checkInTime / checkOutTime 은 placeholder. 가용 포인트
 * 도 placeholder 0 (point_ledger 도메인은 future feature).
 */
public record CheckoutResponse(
        long productId,
        String name,
        BigDecimal basePrice,
        String checkInTime,
        String checkOutTime,
        long availablePoints
) {
}

package com.booking.application;

import com.booking.api.booking.dto.CreateBookingRequest;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * SHA-256(userId|productId|amount|paymentMethod|points) 계산 (ADR-006 §5).
 */
public class BodyHashCalculator {

    /**
     * CreateBookingRequest의 핵심 필드로 SHA-256 hex(64자) 계산.
     * input = userId|productId|amount.toPlainString()|paymentMethod.toUpperCase()|points
     * Phase 3에서 본격 구현.
     */
    public String calculate(CreateBookingRequest request) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }
}

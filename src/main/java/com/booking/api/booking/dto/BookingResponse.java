package com.booking.api.booking.dto;

/**
 * POST /booking 응답 (ADR-006 §3-state — 200 신규/캐시).
 */
public record BookingResponse(
        long bookingId
) {
}

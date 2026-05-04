package com.booking.domain.accommodation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Accommodation Aggregate (ERD §4.7 supporting).
 *
 * <p>본 PR feature-002 minimal — 조회만 사용. immutable record.
 */
public record Accommodation(
        long id,
        String name,
        BigDecimal basePrice,
        Instant createdAt
) {
}

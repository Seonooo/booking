package com.booking.application;

import com.booking.api.checkout.dto.CheckoutResponse;
import com.booking.domain.accommodation.Accommodation;
import com.booking.domain.accommodation.AccommodationRepository;
import org.springframework.stereotype.Service;

/**
 * GET /checkout 처리 (REQUIREMENTS §1.1).
 *
 * <p>본 PR minimal MVP:
 * <ul>
 *   <li>accommodation 조회 — 존재 안 하면 {@link AccommodationNotFoundException}</li>
 *   <li>checkInTime / checkOutTime — placeholder ({@code "15:00"} / {@code "11:00"}) — accommodation 컬럼 추가는 future feature</li>
 *   <li>availablePoints — placeholder 0 (point_ledger 도메인 미구현, future feature)</li>
 * </ul>
 */
@Service
public class CheckoutService {

    private static final String DEFAULT_CHECK_IN_TIME = "15:00";
    private static final String DEFAULT_CHECK_OUT_TIME = "11:00";
    private static final long PLACEHOLDER_AVAILABLE_POINTS = 0L;

    private final AccommodationRepository accommodationRepository;

    public CheckoutService(AccommodationRepository accommodationRepository) {
        this.accommodationRepository = accommodationRepository;
    }

    public CheckoutResponse get(long productId, long userId) {
        Accommodation accommodation = accommodationRepository.findById(productId)
            .orElseThrow(() -> new AccommodationNotFoundException(productId));

        return new CheckoutResponse(
            accommodation.id(),
            accommodation.name(),
            accommodation.basePrice(),
            DEFAULT_CHECK_IN_TIME,
            DEFAULT_CHECK_OUT_TIME,
            PLACEHOLDER_AVAILABLE_POINTS
        );
    }
}

package com.booking.domain.accommodation;

import java.util.Optional;

/**
 * Accommodation driven port (ADR-014). 외부 기술 import 0.
 */
public interface AccommodationRepository {

    Optional<Accommodation> findById(long id);
}

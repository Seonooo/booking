package com.booking.infrastructure.persistence;

import com.booking.domain.accommodation.Accommodation;
import com.booking.domain.accommodation.AccommodationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Driven adapter — domain port {@link AccommodationRepository} 구현체 (ADR-014).
 */
@Component
public class AccommodationRepositoryAdapter implements AccommodationRepository {

    private final AccommodationJpaRepository jpaRepository;

    public AccommodationRepositoryAdapter(AccommodationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Accommodation> findById(long id) {
        return jpaRepository.findById(id).map(AccommodationJpaEntity::toDomain);
    }
}

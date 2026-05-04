package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccommodationJpaRepository extends JpaRepository<AccommodationJpaEntity, Long> {
}

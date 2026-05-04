package com.booking.infrastructure.persistence;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven adapter — domain port {@link BookingRepository} 구현체 (ADR-014).
 */
@Component
public class BookingRepositoryAdapter implements BookingRepository {

    private final BookingJpaRepository jpaRepository;

    public BookingRepositoryAdapter(BookingJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Booking save(Booking booking) {
        BookingJpaEntity persisted = jpaRepository.save(BookingJpaEntity.fromDomain(booking));
        return persisted.toDomain();
    }

    @Override
    @Transactional
    public int casToStatus(long bookingId, BookingStatus from, BookingStatus to) {
        return jpaRepository.casToStatus(bookingId, from.name(), to.name());
    }

    @Override
    public Optional<Booking> findById(long bookingId) {
        return jpaRepository.findById(bookingId).map(BookingJpaEntity::toDomain);
    }

    @Override
    public List<Booking> findStaleByStatusBatch(Instant threshold, int limit) {
        return jpaRepository.findStaleByStatusBatch(threshold, limit).stream()
            .map(BookingJpaEntity::toDomain)
            .toList();
    }
}

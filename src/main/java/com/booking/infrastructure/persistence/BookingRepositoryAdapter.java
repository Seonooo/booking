package com.booking.infrastructure.persistence;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
}

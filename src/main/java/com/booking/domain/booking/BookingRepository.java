package com.booking.domain.booking;

/**
 * Booking driven port (ADR-014). 외부 기술 import 0.
 */
public interface BookingRepository {

    /**
     * Booking 신규 INSERT. id 가 부여된 새 Booking 인스턴스 반환.
     */
    Booking save(Booking booking);

    /**
     * 상태 머신 CAS UPDATE — `from` 상태일 때만 `to` 상태로 전이 (ADR-008 amendment / ERD §6.1).
     *
     * @return 1 = 전이 성공, 0 = 다른 thread 가 이미 전이 또는 row 없음
     */
    int casToStatus(long bookingId, BookingStatus from, BookingStatus to);
}

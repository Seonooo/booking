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

    /**
     * id 로 booking 조회 — sweeper / Saga 보상 / Reconciliation 의 정보 조회용.
     */
    java.util.Optional<Booking> findById(long bookingId);

    /**
     * TTL sweeper 후보 조회 — status IN (HOLD, PG_PENDING) AND created_at &lt; threshold ORDER BY created_at LIMIT.
     */
    java.util.List<Booking> findStaleByStatusBatch(java.time.Instant threshold, int limit);
}

package com.booking.domain.booking;

/**
 * Booking driven port (ADR-014). 외부 기술 import 0.
 *
 * <p>본 PR Phase 3.4 에서는 {@code save} 단일 메소드만 노출. 조회 / 상태 전이는
 * future feature (Stock / Saga / Reconciliation) 진입 시 추가.
 */
public interface BookingRepository {

    /**
     * Booking 신규 INSERT. id 가 부여된 새 Booking 인스턴스 반환.
     */
    Booking save(Booking booking);
}

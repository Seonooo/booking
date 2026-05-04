package com.booking.domain.booking;

/**
 * Booking 상태 머신 (ERD §6.1).
 *
 * <p>본 PR Phase 3.4 에서는 {@link #COMPLETED} 만 활성. 다른 상태는 future feature
 * 진입 시 활성화:
 * <ul>
 *   <li>{@link #HOLD} — 재고 카운터 진입 후, 결제 시작 전 (ADR-008)</li>
 *   <li>{@link #PG_PENDING} — PG 호출 응답 대기 (ADR-011)</li>
 *   <li>{@link #FAILED} — 결제 거절 / Saga 보상 후</li>
 *   <li>{@link #UNKNOWN} — PG timeout (ADR-011 reconciliation 영역)</li>
 * </ul>
 */
public enum BookingStatus {
    HOLD,
    PG_PENDING,
    COMPLETED,
    FAILED,
    UNKNOWN
}

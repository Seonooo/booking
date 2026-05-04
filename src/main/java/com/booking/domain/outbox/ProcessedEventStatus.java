package com.booking.domain.outbox;

/**
 * ProcessedEvent 상태 (ADR-010 amendment §write-first 패턴).
 *
 * <p>{@code INIT} — INSERT 직후 (외부 호출 시도 전). {@code DONE} — 외부 호출 성공 후.
 * 컨슈머 멱등 — 같은 (eventId, consumerName) 두 번째 시도 시 ROW_COUNT==0 → status 확인 후 분기.
 */
public enum ProcessedEventStatus {
    INIT,
    DONE
}

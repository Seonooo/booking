package com.booking.infrastructure.event;

import com.booking.domain.outbox.OutboxEvent;

/**
 * Spring {@code ApplicationEventPublisher} 위임용 wrapper. 본 PR 의 단일 이벤트 — 후속 feature 에서
 * 다른 이벤트 type 추가 시 별 record / class 추가.
 */
public record BookingCompletedEvent(OutboxEvent event) {
}

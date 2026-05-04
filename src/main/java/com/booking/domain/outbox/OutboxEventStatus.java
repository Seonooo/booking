package com.booking.domain.outbox;

/**
 * OutboxEvent 상태 머신 (ERD §4.4 / ADR-010).
 *
 * <p>본 PR 활성: {@code PENDING} (booking save 트랜잭션 안에서 INSERT).
 * {@code PUBLISHED} 전이는 Outbox 폴러 (feature-005) 영역.
 */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED
}

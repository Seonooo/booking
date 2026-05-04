package com.booking.domain.outbox;

/**
 * OutboxEvent driven port (ADR-014).
 *
 * <p>본 PR 메소드: {@link #save} — booking COMPLETED 트랜잭션 안에서 PENDING INSERT.
 * 폴러 / 컨슈머 메소드는 feature-005 에서 추가.
 */
public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);
}

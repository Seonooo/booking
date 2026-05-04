package com.booking.domain.outbox;

/**
 * OutboxEvent driven port (ADR-014).
 */
public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    /**
     * Outbox 폴러 — PENDING 상태 row 를 SELECT FOR UPDATE SKIP LOCKED 로 가져옴.
     * 다중 인스턴스 환경에서 row-level lock 으로 중복 처리 방지 (ADR-010 §결정).
     */
    java.util.List<OutboxEvent> findPendingForUpdate(int batchLimit);

    /**
     * 발행 완료 마킹 — PENDING → PUBLISHED + published_at NOW.
     */
    void markPublished(long id, java.time.Instant publishedAt);
}

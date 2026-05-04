package com.booking.domain.outbox;

/**
 * ProcessedEvent driven port (ADR-010 amendment §write-first + status 패턴).
 *
 * <p>컨슈머 멱등 보장 — `INSERT ... ON DUPLICATE KEY UPDATE` 로 신규/기존 분기.
 */
public interface ProcessedEventRepository {

    /**
     * write-first INSERT — (eventId, consumerName) 가 신규면 ROW_COUNT==1, 이미 존재면 ROW_COUNT==0.
     * 호출자 (컨슈머) 가 ROW_COUNT 분기:
     * <ul>
     *   <li>1: 외부 호출 진행 → 성공 시 {@link #markDone}</li>
     *   <li>0: status 확인 후 skip (이미 처리 중 또는 완료)</li>
     * </ul>
     */
    int tryInsert(long eventId, String consumerName);

    void markDone(long eventId, String consumerName);
}

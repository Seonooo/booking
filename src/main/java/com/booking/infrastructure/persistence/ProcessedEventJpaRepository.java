package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Native SQL only — composite PK (event_id, consumer_name) 의 JPA findById/save 는 본 PR 미사용.
 * ADR-010 amendment §write-first 패턴은 INSERT ... ON DUPLICATE KEY UPDATE 로 처리.
 */
interface ProcessedEventJpaRepository extends Repository<ProcessedEventJpaEntity, Long> {

    /**
     * Write-first INSERT — 신규면 ROW_COUNT==1, 이미 존재면 ROW_COUNT==0 (no-op `event_id=event_id`).
     */
    @Modifying
    @Query(value = "INSERT INTO processed_event (event_id, consumer_name, status, created_at) " +
        "VALUES (:eventId, :consumerName, 'INIT', NOW()) " +
        "ON DUPLICATE KEY UPDATE event_id = event_id",
        nativeQuery = true)
    int tryInsert(@Param("eventId") long eventId, @Param("consumerName") String consumerName);

    @Modifying
    @Query(value = "UPDATE processed_event SET status = 'DONE', processed_at = NOW() " +
        "WHERE event_id = :eventId AND consumer_name = :consumerName",
        nativeQuery = true)
    int markDone(@Param("eventId") long eventId, @Param("consumerName") String consumerName);
}

package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    /**
     * Outbox 폴러 — PENDING row 를 SELECT FOR UPDATE SKIP LOCKED (ADR-010).
     * 다중 인스턴스 환경에서 row-level lock 으로 중복 처리 방지. 호출자 (OutboxPoller) 의
     * 트랜잭션 안에서 호출되어야 lock 유지.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' " +
        "ORDER BY created_at LIMIT :batchLimit FOR UPDATE SKIP LOCKED",
        nativeQuery = true)
    List<OutboxEventJpaEntity> findPendingForUpdate(@Param("batchLimit") int batchLimit);

    @Modifying
    @Query(value = "UPDATE outbox_event SET status = 'PUBLISHED', published_at = :publishedAt " +
        "WHERE id = :id",
        nativeQuery = true)
    int markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);
}

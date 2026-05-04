package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingJpaRepository extends JpaRepository<BookingJpaEntity, Long> {

    /**
     * 상태 머신 CAS UPDATE — `from` 상태일 때만 `to` 상태로 전이 (ADR-008 amendment / ERD §6.1).
     *
     * <p>ROW_COUNT == 1: 전이 성공. ROW_COUNT == 0: 다른 thread 가 이미 전이 또는 row 없음.
     */
    @Modifying
    @Query(value = "UPDATE booking SET status = :to, updated_at = NOW() " +
        "WHERE id = :id AND status = :from",
        nativeQuery = true)
    int casToStatus(@Param("id") long id,
                    @Param("from") String from,
                    @Param("to") String to);

    /**
     * TTL sweeper 후보 조회 — booking.status IN (HOLD, PG_PENDING) AND created_at &lt; threshold.
     * ORDER BY created_at — 가장 오래된 row 먼저 sweep. idx_booking_status_updated 사용.
     */
    @Query(value = "SELECT * FROM booking " +
        "WHERE status IN ('HOLD', 'PG_PENDING') AND created_at < :threshold " +
        "ORDER BY created_at LIMIT :batchLimit",
        nativeQuery = true)
    java.util.List<BookingJpaEntity> findStaleByStatusBatch(
        @Param("threshold") java.time.Instant threshold,
        @Param("batchLimit") int batchLimit);
}

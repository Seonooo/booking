package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptJpaEntity, Long> {

    /**
     * CAS UPDATE — INIT/TIMEOUT 상태에서만 REQUESTED 진입 (ADR-011 §retry CAS).
     *
     * <p>ROW_COUNT == 1: 진입 성공. ROW_COUNT == 0: 다른 thread 가 이미 REQUESTED.
     */
    @Modifying
    @Query(value = "UPDATE payment_attempt SET status = 'REQUESTED', last_requested_at = NOW() " +
        "WHERE id = :id AND status IN ('INIT', 'TIMEOUT')",
        nativeQuery = true)
    int casToRequested(@Param("id") long id);

    @Modifying
    @Query(value = "UPDATE payment_attempt SET status = :status, external_payment_id = :externalPaymentId " +
        "WHERE id = :id",
        nativeQuery = true)
    int updateToTerminal(@Param("id") long id,
                         @Param("status") String status,
                         @Param("externalPaymentId") String externalPaymentId);

    /**
     * Reconciliation worker 후보 조회 (ADR-011 §결정 2 트리거 + 코드 레벨 guard).
     *
     * <p>WHERE: status='TIMEOUT' AND (last_reconcile_at IS NULL OR last_reconcile_at &lt; threshold)
     *           AND reconcile_retry_count &lt;= 3.
     * <p>last_reconcile_at &lt; 30s 이내 skip 은 호출자 (worker) 가 threshold 계산.
     */
    @Query(value = "SELECT * FROM payment_attempt " +
        "WHERE status = 'TIMEOUT' " +
        "AND (last_reconcile_at IS NULL OR last_reconcile_at < :threshold) " +
        "AND reconcile_retry_count <= 3 " +
        "ORDER BY updated_at LIMIT :batchLimit",
        nativeQuery = true)
    java.util.List<PaymentAttemptJpaEntity> findStaleUnknown(
        @Param("threshold") java.time.Instant threshold,
        @Param("batchLimit") int batchLimit);

    @Modifying
    @Query(value = "UPDATE payment_attempt " +
        "SET reconcile_retry_count = reconcile_retry_count + 1, last_reconcile_at = NOW() " +
        "WHERE id = :id",
        nativeQuery = true)
    int incrementRetryCount(@Param("id") long id);
}

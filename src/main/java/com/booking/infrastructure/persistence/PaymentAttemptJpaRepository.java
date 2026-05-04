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
}

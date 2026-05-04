package com.booking.domain.payment_attempt;

/**
 * PaymentAttempt driven port (ADR-014).
 *
 * <p>본 PR 메소드:
 * <ul>
 *   <li>{@link #save} — INIT 상태 INSERT</li>
 *   <li>{@link #casToRequested} — CAS UPDATE (status IN ('INIT','TIMEOUT') → 'REQUESTED' + last_requested_at NOW)</li>
 *   <li>{@link #updateToTerminal} — SUCCESS / FAILED / TIMEOUT 진입 + external_payment_id (nullable)</li>
 * </ul>
 */
public interface PaymentAttemptRepository {

    PaymentAttempt save(PaymentAttempt paymentAttempt);

    /**
     * @return 1 = CAS 성공, 0 = 다른 thread 가 먼저 진입 (본 PR happy path 에선 항상 1)
     */
    int casToRequested(long id);

    void updateToTerminal(long id, PaymentAttemptStatus status, String externalPaymentId);

    /**
     * Reconciliation worker (feature-007) 후보 조회 — TIMEOUT status + last_reconcile_at NULL 또는
     * threshold 이전 + reconcile_retry_count &lt;= 3.
     */
    java.util.List<PaymentAttempt> findStaleUnknown(java.time.Instant lastReconcileBefore, int batchLimit);

    /**
     * Reconciliation 결과 NOT_FOUND 시 — last_reconcile_at NOW + retry_count++.
     */
    void incrementRetryCount(long id);
}

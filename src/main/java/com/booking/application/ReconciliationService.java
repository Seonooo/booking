package com.booking.application;

import com.booking.domain.payment_attempt.PaymentAttempt;
import org.springframework.stereotype.Service;

/**
 * PG 상태 조회 + booking UNKNOWN 결과 확정 (ADR-011) — Phase 3.4 GREEN 에서 본격 구현.
 *
 * <p>Phase 2 RED stub — test 가 reconcileBatch / reconcileOne 호출 시 nothing happens.
 */
@Service
public class ReconciliationService {

    public void reconcileBatch() {
        // Phase 3.4 GREEN 에서 본격 구현
    }

    public void reconcileOne(PaymentAttempt attempt) {
        // Phase 3.4 GREEN 에서 본격 구현
    }
}

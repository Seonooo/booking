package com.booking.infrastructure.scheduler;

import com.booking.application.ReconciliationService;
import org.springframework.stereotype.Component;

/**
 * @Scheduled + @SchedulerLock PG reconciliation worker — Phase 3.5 GREEN 에서 본격 구현.
 *
 * <p>Phase 2 RED stub — Bean 등록만. @Scheduled 미부여라 자동 trigger X.
 */
@Component
public class ReconciliationWorker {

    private final ReconciliationService reconciliationService;

    public ReconciliationWorker(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }
}

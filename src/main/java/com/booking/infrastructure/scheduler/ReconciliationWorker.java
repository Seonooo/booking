package com.booking.infrastructure.scheduler;

import com.booking.application.ReconciliationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @Scheduled + @SchedulerLock PG reconciliation worker (ADR-011).
 *
 * <p>1분 주기로 booking UNKNOWN row 의 PG 상태 조회 + 결과 확정. 다중 인스턴스 환경에서
 * ShedLock 분산 락 (`pg-reconciliation`) 으로 한 인스턴스만 진입 — PG 부담 ↓, 중복 처리 회피.
 *
 * <p>락 시간:
 * <ul>
 *   <li>{@code lockAtMostFor=PT5M} — 한 batch 최대 5분 (worker 다운 시 lock 자동 해제)</li>
 *   <li>{@code lockAtLeastFor=PT30S} — fast finish 시 즉시 재진입 차단 (clock skew 보호)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "scheduler.pg-reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationWorker {

    private final ReconciliationService reconciliationService;

    public ReconciliationWorker(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "pg-reconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        reconciliationService.reconcileBatch();
    }
}

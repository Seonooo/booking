package com.booking.infrastructure.scheduler;

import com.booking.application.BookingHoldSweepService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @Scheduled + @SchedulerLock booking HOLD/PG_PENDING TTL sweeper (ADR-008 amendment / ADR-010 ShedLock).
 *
 * <p>30초 주기로 sweep 시도. 다중 인스턴스 환경에서 ShedLock 분산 락 (`booking-hold-sweeper`)
 * 으로 한 인스턴스만 진입 — DB 부담 ↓, 중복 처리 회피.
 *
 * <p>락 시간:
 * <ul>
 *   <li>{@code lockAtMostFor=PT4M} — 한 batch 최대 4분 (worker 다운 시 lock 자동 해제 시간)</li>
 *   <li>{@code lockAtLeastFor=PT10S} — fast finish 시 다른 worker 가 즉시 재진입 못 함 (clock skew 보호)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "scheduler.booking-hold-sweeper", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BookingHoldSweeper {

    private final BookingHoldSweepService sweepService;

    public BookingHoldSweeper(BookingHoldSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "booking-hold-sweeper", lockAtMostFor = "PT4M", lockAtLeastFor = "PT10S")
    public void sweep() {
        sweepService.sweepBatch();
    }
}

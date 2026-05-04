package com.booking.infrastructure.scheduler;

import com.booking.application.OutboxPollService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @Scheduled + @SchedulerLock Outbox 폴러 (ADR-010).
 *
 * <p>비즈니스 로직은 {@link OutboxPollService} 위임 — SRP. 본 클래스는 *자동 trigger* 만 책임.
 * test 환경에선 {@code scheduler.outbox-poller.enabled=false} 로 자동 trigger 비활성 (test 가 service 직접 호출).
 *
 * <p>BookingHoldSweeper / ReconciliationWorker 패턴 정합.
 */
@Component
@ConditionalOnProperty(prefix = "scheduler.outbox-poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxPollService pollService;

    public OutboxPoller(OutboxPollService pollService) {
        this.pollService = pollService;
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox-poller", lockAtMostFor = "PT4M", lockAtLeastFor = "PT3S")
    public void poll() {
        pollService.pollBatch();
    }
}

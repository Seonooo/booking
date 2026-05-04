package com.booking.infrastructure.scheduler;

import com.booking.domain.outbox.EventPublisher;
import com.booking.domain.outbox.OutboxEvent;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.booking.domain.outbox.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * @Scheduled + @SchedulerLock Outbox 폴러 (ADR-010 §결정 축 2 — 5초 폴링).
 *
 * <p>다중 인스턴스 환경 — ShedLock 분산 락 (`outbox-poller`) + DB row-level lock (SELECT FOR
 * UPDATE SKIP LOCKED) 이중 보호.
 *
 * <p>흐름:
 * <ol>
 *   <li>findPendingForUpdate(100) — PENDING row lock + 가져옴</li>
 *   <li>각 row: EventPublisher.publish (In-Process — Spring ApplicationEventPublisher)</li>
 *   <li>publish 성공 시 markPublished — status PENDING → PUBLISHED + published_at NOW</li>
 *   <li>한 row publish 실패 시 log only, 다음 row 계속 (한 batch 안에서 부분 실패 허용)</li>
 * </ol>
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_LIMIT = 100;

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transactionTemplate;

    public OutboxPoller(OutboxEventRepository repository,
                        EventPublisher publisher,
                        TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox-poller", lockAtMostFor = "PT4M", lockAtLeastFor = "PT3S")
    public void poll() {
        pollBatch();
    }

    public void pollBatch() {
        // findPendingForUpdate 가 SELECT FOR UPDATE SKIP LOCKED — 트랜잭션 안에서 호출되어야 lock 유지.
        // 발행 + markPublished 까지 단일 트랜잭션 안에서 — lock 보유 동안 다른 인스턴스 같은 row 못 잡음.
        transactionTemplate.executeWithoutResult(status -> {
            List<OutboxEvent> pending = repository.findPendingForUpdate(BATCH_LIMIT);
            if (pending.isEmpty()) {
                return;
            }
            log.info("[OUTBOX_POLL_BATCH_START] candidates={}", pending.size());
            Instant now = Instant.now();
            for (OutboxEvent event : pending) {
                try {
                    publisher.publish(event);
                    repository.markPublished(event.getId(), now);
                } catch (Exception e) {
                    // 한 row 실패 — log + 다음 row 계속 (PENDING 유지, 다음 cycle 재시도)
                    log.error("[OUTBOX_PUBLISH_FAILED] eventId={} eventType={} message={}",
                        event.getId(), event.getEventType(), e.getMessage(), e);
                }
            }
        });
    }
}

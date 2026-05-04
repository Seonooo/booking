package com.booking.application;

import com.booking.domain.outbox.EventPublisher;
import com.booking.domain.outbox.OutboxEvent;
import com.booking.domain.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 폴링 비즈니스 로직 (ADR-010 §결정 축 2 — 5초 폴링).
 *
 * <p>SRP — 본 service 가 *pollBatch* 비즈니스 로직 담당. {@link com.booking.infrastructure.scheduler.OutboxPoller}
 * 는 {@code @Scheduled + @SchedulerLock} 자동 trigger 만 책임. test 는 본 service 직접 호출.
 *
 * <p>BookingHoldSweepService / ReconciliationService 패턴 정합.
 */
@Service
public class OutboxPollService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollService.class);
    private static final int BATCH_LIMIT = 100;

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transactionTemplate;

    public OutboxPollService(OutboxEventRepository repository,
                             EventPublisher publisher,
                             TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
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

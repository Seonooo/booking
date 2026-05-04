package com.booking.infrastructure.event;

import com.booking.domain.outbox.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * In-Process 컨슈머 — write-first {@code processed_event} 패턴 (ADR-010 amendment §컨슈머 멱등).
 *
 * <p>흐름:
 * <ol>
 *   <li>{@code tryInsert(eventId, consumerName)} — ROW_COUNT 분기</li>
 *   <li>1 (신규 INSERT): 외부 부수효과 (본 PR 은 log) → {@code markDone}</li>
 *   <li>0 (이미 존재): skip — 다른 컨슈머 또는 재발행 시도라 외부 부수효과 1회만 보장</li>
 * </ol>
 *
 * <p>본 PR 은 로깅 컨슈머 1개 — 후속 feature 에서 알림 / 정산 등 컨슈머 추가 가능.
 */
@Component
public class LoggingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventConsumer.class);
    private static final String CONSUMER_NAME = "LoggingConsumer";

    private final ProcessedEventRepository processedEventRepository;

    public LoggingEventConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @EventListener
    public void onBookingCompleted(BookingCompletedEvent event) {
        Long eventId = event.event().getId();
        if (eventId == null) {
            log.warn("[CONSUMER_NULL_EVENT_ID] eventType={}", event.event().getEventType());
            return;
        }
        int row = processedEventRepository.tryInsert(eventId, CONSUMER_NAME);
        if (row != 1) {
            log.debug("[CONSUMER_SKIP_ALREADY_PROCESSED] eventId={} consumer={}",
                eventId, CONSUMER_NAME);
            return;
        }
        // 외부 부수효과 — 본 PR 은 log only. 후속 feature (알림 / 정산) 진입 시 본격.
        log.info("[CONSUMER_LOG] eventId={} eventType={} payload={}",
            eventId, event.event().getEventType(), event.event().getPayload());
        processedEventRepository.markDone(eventId, CONSUMER_NAME);
    }
}

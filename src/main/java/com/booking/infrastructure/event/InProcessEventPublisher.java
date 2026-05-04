package com.booking.infrastructure.event;

import com.booking.domain.outbox.EventPublisher;
import com.booking.domain.outbox.OutboxEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-Process EventPublisher (ADR-010 §결정 축 3) — Spring {@link ApplicationEventPublisher} 위임.
 * 50 TPS 환경에서 부담 0. fan-out 필요 시 Kafka 등 별 구현체로 교체.
 */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private final ApplicationEventPublisher publisher;

    public InProcessEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OutboxEvent event) {
        publisher.publishEvent(new BookingCompletedEvent(event));
    }
}

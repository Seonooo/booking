package com.booking.domain.outbox;

/**
 * Outbox event publisher driven port (ADR-010 §결정 축 4 EventPublisher 인터페이스).
 *
 * <p>구현체 교체로 이벤트 도구 전환 가능 (현재 In-Process / future Kafka). YAGNI 정합 —
 * 인터페이스 한 단계 추가만으로 도구 전환 비용 ↓.
 */
public interface EventPublisher {

    void publish(OutboxEvent event);
}

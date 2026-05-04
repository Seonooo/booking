package com.booking.infrastructure.persistence;

import com.booking.domain.outbox.ProcessedEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA mapping for {@code processed_event} table (ERD §4.5 / ADR-010 amendment).
 *
 * <p>composite PK (event_id, consumer_name) — {@link Pk} static class 로 매핑.
 * 본 PR 의 JPA save / findById 미사용 — Native SQL ON DUPLICATE KEY UPDATE 로 처리.
 * Entity 정의는 V1 schema 매핑 검증용.
 */
@Entity
@Table(name = "processed_event")
@IdClass(ProcessedEventJpaEntity.Pk.class)
public class ProcessedEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private long eventId;

    @Id
    @Column(name = "consumer_name", length = 50)
    private String consumerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProcessedEventStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProcessedEventJpaEntity() {
        // JPA spec
    }

    /**
     * composite PK class — ERD §4.5 (event_id BIGINT + consumer_name VARCHAR(50)).
     */
    public static class Pk implements Serializable {
        private long eventId;
        private String consumerName;

        public Pk() {}

        public Pk(long eventId, String consumerName) {
            this.eventId = eventId;
            this.consumerName = consumerName;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return eventId == pk.eventId && Objects.equals(consumerName, pk.consumerName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerName);
        }
    }
}

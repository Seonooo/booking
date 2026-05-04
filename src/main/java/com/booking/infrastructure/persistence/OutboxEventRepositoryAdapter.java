package com.booking.infrastructure.persistence;

import com.booking.domain.outbox.OutboxEvent;
import com.booking.domain.outbox.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Driven adapter — domain port {@link OutboxEventRepository} 구현체 (ADR-014).
 */
@Component
public class OutboxEventRepositoryAdapter implements OutboxEventRepository {

    private final OutboxEventJpaRepository jpaRepository;

    public OutboxEventRepositoryAdapter(OutboxEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public OutboxEvent save(OutboxEvent outboxEvent) {
        OutboxEventJpaEntity persisted = jpaRepository.save(OutboxEventJpaEntity.fromDomain(outboxEvent));
        return persisted.toDomain();
    }

    @Override
    public List<OutboxEvent> findPendingForUpdate(int batchLimit) {
        return jpaRepository.findPendingForUpdate(batchLimit).stream()
            .map(OutboxEventJpaEntity::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public void markPublished(long id, Instant publishedAt) {
        jpaRepository.markPublished(id, publishedAt);
    }
}

package com.booking.infrastructure.persistence;

import com.booking.domain.outbox.ProcessedEventRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpaRepository;

    public ProcessedEventRepositoryAdapter(ProcessedEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public int tryInsert(long eventId, String consumerName) {
        return jpaRepository.tryInsert(eventId, consumerName);
    }

    @Override
    @Transactional
    public void markDone(long eventId, String consumerName) {
        jpaRepository.markDone(eventId, consumerName);
    }
}

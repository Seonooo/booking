package com.booking.infrastructure.persistence;

import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter — domain port {@link IdempotencyKeyRepository} 구현체.
 *
 * <p>{@link IdempotencyKeyJpaRepository} 의 entity-level CRUD 를 domain
 * aggregate 시그니처로 변환. 이 분리는 두 인터페이스의 generic 충돌
 * (JpaRepository&lt;Entity, UUID&gt; vs IdempotencyKeyRepository) 을 회피하고
 * 매핑 책임을 한 곳에 모은다 (ADR-014).
 */
@Component
public class IdempotencyKeyRepositoryAdapter implements IdempotencyKeyRepository {

    private final IdempotencyKeyJpaRepository jpaRepository;

    public IdempotencyKeyRepositoryAdapter(IdempotencyKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<IdempotencyKey> findById(UUID idempotencyKey) {
        return jpaRepository.findById(idempotencyKey)
            .map(IdempotencyKeyJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(IdempotencyKey idempotencyKey) {
        jpaRepository.save(IdempotencyKeyJpaEntity.fromDomain(idempotencyKey));
    }

    @Override
    @Transactional
    public void updateToCompleted(UUID idempotencyKey, String responsePayload, long bookingId) {
        jpaRepository.updateToCompleted(idempotencyKey, responsePayload, bookingId);
    }
}

package com.booking.infrastructure.persistence;

import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyKeyRepositoryAdapter(IdempotencyKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<IdempotencyKey> findById(UUID idempotencyKey) {
        return jpaRepository.findById(idempotencyKey)
            .map(IdempotencyKeyJpaEntity::toDomain);
    }

    /**
     * 명시적 INSERT — Spring Data {@code save()} 는 client-set ID 를 *기존*으로
     * 간주해 merge(UPDATE) 로 동작하므로 ADR-006 §DB 2차 방어선의 UNIQUE 충돌
     * 의도가 우회된다. {@link EntityManager#persist} 는 INSERT 만 시도하므로 PK
     * 충돌 시 {@code DataIntegrityViolationException} 으로 변환되어 503 응답을
     * 거친다 (GlobalExceptionHandler).
     */
    @Override
    @Transactional
    public void save(IdempotencyKey idempotencyKey) {
        entityManager.persist(IdempotencyKeyJpaEntity.fromDomain(idempotencyKey));
    }

    @Override
    @Transactional
    public void updateToCompleted(UUID idempotencyKey, String responsePayload, long bookingId) {
        jpaRepository.updateToCompleted(idempotencyKey, responsePayload, bookingId);
    }
}

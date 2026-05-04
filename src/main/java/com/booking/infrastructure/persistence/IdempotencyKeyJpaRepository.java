package com.booking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link IdempotencyKeyJpaEntity}.
 *
 * <p>Infrastructure layer 내부에 위치 — domain port {@link
 * com.booking.domain.idempotency.IdempotencyKeyRepository} 의 구현은
 * {@link IdempotencyKeyRepositoryAdapter} 가 본 인터페이스를 위임 사용한다.
 * 두 단계 분리는 JPA 시그니처 (Optional&lt;Entity&gt;) 와 domain port 시그니처
 * (Optional&lt;Aggregate&gt;) 사이 매핑 책임을 adapter 에 두기 위한 것.
 */
@Repository
public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyJpaEntity, UUID> {

    /**
     * PROCESSING → COMPLETED 전이 단일 UPDATE — Aggregate load 우회.
     */
    @Modifying
    @Query("UPDATE IdempotencyKeyJpaEntity e " +
        "SET e.status = com.booking.domain.idempotency.IdempotencyStatus.COMPLETED, " +
        "    e.responsePayload = :responsePayload, " +
        "    e.bookingId = :bookingId " +
        "WHERE e.idempotencyKey = :key")
    int updateToCompleted(@Param("key") UUID key,
                          @Param("responsePayload") String responsePayload,
                          @Param("bookingId") long bookingId);
}

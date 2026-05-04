package com.booking.infrastructure.persistence;

import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptRepository;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Driven adapter — domain port {@link PaymentAttemptRepository} 구현체 (ADR-014).
 */
@Component
public class PaymentAttemptRepositoryAdapter implements PaymentAttemptRepository {

    private final PaymentAttemptJpaRepository jpaRepository;

    public PaymentAttemptRepositoryAdapter(PaymentAttemptJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public PaymentAttempt save(PaymentAttempt paymentAttempt) {
        PaymentAttemptJpaEntity persisted = jpaRepository.save(
            PaymentAttemptJpaEntity.fromDomain(paymentAttempt));
        return persisted.toDomain();
    }

    @Override
    @Transactional
    public int casToRequested(long id) {
        return jpaRepository.casToRequested(id);
    }

    @Override
    @Transactional
    public void updateToTerminal(long id, PaymentAttemptStatus status, String externalPaymentId) {
        jpaRepository.updateToTerminal(id, status.name(), externalPaymentId);
    }

    @Override
    public List<PaymentAttempt> findStaleUnknown(Instant threshold, int batchLimit) {
        return jpaRepository.findStaleUnknown(threshold, batchLimit).stream()
            .map(PaymentAttemptJpaEntity::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public void incrementRetryCount(long id) {
        jpaRepository.incrementRetryCount(id);
    }
}

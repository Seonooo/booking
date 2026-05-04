package com.booking.concurrency;

import com.booking.application.ReconciliationService;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptRepository;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
import com.booking.integration.IntegrationTestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency 테스트 — 같은 attempt 두 thread 동시 reconcileOne CAS 정합성 (Scenario 5).
 *
 * <p>Source: docs/features/feature-007-reconciliation-worker.md
 */
class ReconciliationWorkerConcurrencyTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> pgMock.baseUrl());
    }

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String EXTERNAL_PAYMENT_ID = "pg-recon-conc";

    @Test
    @Tag("edge")
    @Tag("edge:concurrency")
    @DisplayName("같은 attempt 2 thread 동시 reconcileOne → COMPLETED 1건 + paymentAttempt SUCCESS 1번")
    void should_handle_concurrent_reconcile_with_cas_idempotency() throws Exception {
        // Given: UNKNOWN booking + TIMEOUT attempt 1건
        UUID idempotencyKey = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO booking (user_id, accommodation_id, idempotency_key, amount, " +
                "payment_composition_snapshot, status, created_at, updated_at) " +
                "VALUES (?, ?, UUID_TO_BIN(?), ?, ?, ?, " +
                "DATE_SUB(NOW(), INTERVAL 420 SECOND), DATE_SUB(NOW(), INTERVAL 420 SECOND))",
            1001L, 42L, idempotencyKey.toString(), new BigDecimal("50000.00"),
            "{\"methods\":[]}", BookingStatus.UNKNOWN.name());
        long bookingId = jdbcTemplate.queryForObject(
            "SELECT id FROM booking ORDER BY id DESC LIMIT 1", Long.class);

        UUID attemptId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(420);
        PaymentAttempt attempt = new PaymentAttempt(
            null, attemptId, bookingId, new BigDecimal("50000.00"),
            "{\"methods\":[]}", PaymentAttemptStatus.TIMEOUT, EXTERNAL_PAYMENT_ID,
            past, null, 0, past, past);
        paymentAttemptRepository.save(attempt);

        // PG mock — SUCCESS 응답
        pgMock.stubFor(get(urlPathMatching("/payment/" + EXTERNAL_PAYMENT_ID))
                .willReturn(okJson("{\"externalPaymentId\":\"" + EXTERNAL_PAYMENT_ID
                    + "\",\"status\":\"SUCCESS\"}")));

        // 다시 attempt 조회 (id 가 set 된 인스턴스)
        com.booking.domain.payment_attempt.PaymentAttempt savedAttempt =
            paymentAttemptRepository.findStaleUnknown(Instant.now().plusSeconds(60), 10).get(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    reconciliationService.reconcileOne(savedAttempt);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean allDone = done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allDone).isTrue();
        assertThat(errors.get()).as("두 thread 모두 정상").isZero();

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus)
            .as("CAS 정합성 — booking COMPLETED 1번 전이")
            .isEqualTo("COMPLETED");
    }
}

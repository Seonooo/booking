package com.booking.integration;

import com.booking.application.ReconciliationService;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptRepository;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — Reconciliation worker (Scenario 1, 2, 3, 4, 6).
 *
 * <p>ADR-011 §결정 2 — PG 상태 조회 → booking UNKNOWN 결과 확정. 핵심 원칙 — NOT_FOUND ≠ FAILED,
 * retry 소진만으로 FAILED 전이 금지.
 *
 * <p>Source: docs/features/feature-007-reconciliation-worker.md
 */
class ReconciliationWorkerIntegrationTest extends IntegrationTestSupport {

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY = "stock:accommodation:42";
    private static final String EXTERNAL_PAYMENT_ID = "pg-recon-001";

    /**
     * UNKNOWN booking + TIMEOUT paymentAttempt fixture. attempt 의 last_reconcile_at /
     * reconcile_retry_count 직접 SQL UPDATE 로 set (도메인 record 의 lastReconcileAt 생성자 활용도 가능).
     */
    private long insertUnknownBookingWithStaleAttempt(int retryCount, Integer lastReconcileSecondsAgo) {
        // 1. booking UNKNOWN INSERT (created_at = now-7분 — 6분 threshold 초과)
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

        // 2. paymentAttempt TIMEOUT INSERT — domain 객체 사용 (reconcile fields)
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant lastReconcileAt = lastReconcileSecondsAgo == null
            ? null : now.minusSeconds(lastReconcileSecondsAgo);
        PaymentAttempt attempt = new PaymentAttempt(
            null, attemptId, bookingId, new BigDecimal("50000.00"),
            "{\"methods\":[]}", PaymentAttemptStatus.TIMEOUT, EXTERNAL_PAYMENT_ID,
            now.minusSeconds(420), lastReconcileAt, retryCount, now.minusSeconds(420), now.minusSeconds(420));
        paymentAttemptRepository.save(attempt);
        return bookingId;
    }

    private long latestAttemptId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM payment_attempt ORDER BY id DESC LIMIT 1", Long.class);
    }

    // =========================================================================
    // Scenario 1: [happy] PG SUCCESS → booking COMPLETED + paymentAttempt SUCCESS
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("PG SUCCESS → booking COMPLETED + paymentAttempt SUCCESS")
    void should_complete_booking_when_pg_returns_success() {
        long bookingId = insertUnknownBookingWithStaleAttempt(0, null);
        pgMock.stubFor(get(urlPathMatching("/payment/" + EXTERNAL_PAYMENT_ID))
                .willReturn(okJson("{\"externalPaymentId\":\"" + EXTERNAL_PAYMENT_ID
                    + "\",\"status\":\"SUCCESS\"}")));

        reconciliationService.reconcileBatch();

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("COMPLETED");

        String paStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempt WHERE booking_id = ?", String.class, bookingId);
        assertThat(paStatus).isEqualTo("SUCCESS");
    }

    // =========================================================================
    // Scenario 2: [happy] PG FAILED → booking FAILED + stock INCR
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("PG FAILED → booking FAILED + paymentAttempt FAILED + stock INCR")
    void should_fail_booking_and_release_stock_when_pg_returns_failed() {
        long bookingId = insertUnknownBookingWithStaleAttempt(0, null);
        redisTemplate.opsForValue().set(STOCK_KEY, "9");
        redisTemplate.opsForValue().set("hold:user:1001:product:42", "HOLD");
        pgMock.stubFor(get(urlPathMatching("/payment/" + EXTERNAL_PAYMENT_ID))
                .willReturn(okJson("{\"externalPaymentId\":\"" + EXTERNAL_PAYMENT_ID
                    + "\",\"status\":\"FAILED\"}")));

        reconciliationService.reconcileBatch();

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("FAILED");

        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
            .as("stock INCR 9 → 10")
            .isEqualTo("10");
        assertThat(redisTemplate.hasKey("hold:user:1001:product:42"))
            .as("hold key cleanup")
            .isFalse();
    }

    // =========================================================================
    // Scenario 3: [edge:failure] PG NOT_FOUND → UNKNOWN 유지 + retry_count++
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG NOT_FOUND → UNKNOWN 유지 + retry_count++ + last_reconcile_at set")
    void should_keep_unknown_and_increment_retry_when_pg_not_found() {
        long bookingId = insertUnknownBookingWithStaleAttempt(0, null);
        pgMock.stubFor(get(urlPathMatching("/payment/" + EXTERNAL_PAYMENT_ID))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"NOT_FOUND\"}")));

        reconciliationService.reconcileBatch();

        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus)
            .as("UNKNOWN 유지 (NOT_FOUND ≠ FAILED, ADR-011 §핵심 원칙)")
            .isEqualTo("UNKNOWN");

        Integer retryCount = jdbcTemplate.queryForObject(
            "SELECT reconcile_retry_count FROM payment_attempt WHERE booking_id = ?",
            Integer.class, bookingId);
        assertThat(retryCount).isEqualTo(1);

        Object lastReconcileAt = jdbcTemplate.queryForObject(
            "SELECT last_reconcile_at FROM payment_attempt WHERE booking_id = ?",
            Object.class, bookingId);
        assertThat(lastReconcileAt).as("last_reconcile_at set").isNotNull();
    }

    // =========================================================================
    // Scenario 4: [edge:failure] retry_count >= 3 → escalation 로그 + UNKNOWN 유지
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("retry_count >= 3 + NOT_FOUND → UNKNOWN 유지 (retry 소진 ≠ FAILED)")
    void should_keep_unknown_when_retry_exhausted() {
        long bookingId = insertUnknownBookingWithStaleAttempt(3, 60); // retry=3, last_reconcile 60s 전
        pgMock.stubFor(get(urlPathMatching("/payment/" + EXTERNAL_PAYMENT_ID))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"NOT_FOUND\"}")));

        reconciliationService.reconcileBatch();

        // ADR-011 §핵심 원칙 — retry 소진만으로 FAILED 전이 금지. UNKNOWN 유지 + 운영자 에스컬레이션.
        String bookingStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(bookingStatus).isEqualTo("UNKNOWN");
    }

    // =========================================================================
    // Scenario 6: [edge:boundary] last_reconcile_at < 30s — skip (in-flight 보호)
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:boundary")
    @DisplayName("last_reconcile_at 20s 전 → reconcileBatch findStaleUnknown skip (PG 호출 0회)")
    void should_skip_when_reconciled_within_30s() {
        insertUnknownBookingWithStaleAttempt(0, 20); // last_reconcile 20s 전 (threshold 30s 미만)
        pgMock.stubFor(get(urlPathMatching("/payment/.*"))
                .willReturn(okJson("{\"status\":\"SUCCESS\"}")));

        reconciliationService.reconcileBatch();

        // findStaleUnknown 가 last_reconcile_at < 30s 를 제외 → PG 조회 0회
        pgMock.verify(0, getRequestedFor(urlPathMatching("/payment/.*")));
    }
}

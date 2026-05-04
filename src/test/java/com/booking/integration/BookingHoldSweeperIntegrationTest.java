package com.booking.integration;

import com.booking.application.BookingHoldSweepService;
import com.booking.domain.booking.BookingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — booking HOLD/PG_PENDING TTL sweeper (Scenario 1, 2, 4).
 *
 * <p>ADR-008 amendment 정합 — TTL 만료 시 sweeper 가 INCR. PG 결제 거절 시도 같은 흐름
 * (booking FAILED 마킹 → 5분 TTL 만료 시 sweeper 가 INCR).
 *
 * <p>Source: docs/features/feature-006-ttl-sweeper.md
 */
class BookingHoldSweeperIntegrationTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        // Spring context cache key 정합 — BookingIdempotency*Test / BookingSaga*Test 와
        // 같은 cache key 로 공유 context 사용 (다중 context 시 컨테이너 lifecycle race 회피).
        // 본 test 는 PG 호출 없지만 dummy URL 로 cache hit.
        registry.add("external.pg.url", () -> "http://localhost:9999");
    }

    @Autowired
    private BookingHoldSweepService sweepService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY = "stock:accommodation:42";
    private static final String HOLD_KEY = "hold:user:1001:product:42";

    /**
     * 시간 임계 fixture — booking row 를 (NOW - secondsAgo) created_at + updated_at 으로 직접 INSERT.
     * Clock 조작 대신 DB 시간 직접 set 으로 sweeper 의 stale 후보 진입 검증.
     */
    private long insertStaleBooking(int secondsAgo, BookingStatus status) {
        UUID key = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO booking (user_id, accommodation_id, idempotency_key, amount, " +
                "payment_composition_snapshot, status, created_at, updated_at) " +
                "VALUES (?, ?, UUID_TO_BIN(?), ?, ?, ?, " +
                "DATE_SUB(NOW(), INTERVAL ? SECOND), DATE_SUB(NOW(), INTERVAL ? SECOND))",
            1001L, 42L, key.toString(), new BigDecimal("50000.00"),
            "{\"methods\":[]}", status.name(), secondsAgo, secondsAgo);
        return jdbcTemplate.queryForObject(
            "SELECT id FROM booking ORDER BY id DESC LIMIT 1", Long.class);
    }

    // =========================================================================
    // Scenario 1: [happy] HOLD 6분 전 → sweep + INCR + hold key DEL
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("HOLD 6분 전 → sweep + booking FAILED + stock INCR + hold key DEL")
    void should_sweep_expired_hold_and_release_stock() {
        // Given: stock=9 (1개 hold), hold key 존재, booking HOLD 6분 전
        long bookingId = insertStaleBooking(360, BookingStatus.HOLD);
        redisTemplate.opsForValue().set(STOCK_KEY, "9");
        redisTemplate.opsForValue().set(HOLD_KEY, "HOLD", 300, TimeUnit.SECONDS);

        // When
        sweepService.sweepBatch();

        // Then: booking FAILED, stock 10, hold key cleanup
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(status)
            .as("HOLD → FAILED CAS 통과")
            .isEqualTo("FAILED");
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
            .as("stock INCR 9 → 10")
            .isEqualTo("10");
        assertThat(redisTemplate.hasKey(HOLD_KEY))
            .as("hold key cleanup")
            .isFalse();
    }

    // =========================================================================
    // Scenario 2: [edge:boundary] HOLD 4분 전 (TTL 미만) → sweep skip
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:boundary")
    @DisplayName("HOLD 4분 전 → sweep skip (TTL threshold 미만)")
    void should_skip_when_hold_within_ttl() {
        // Given: HOLD 240초 전 (TTL 360s 미만)
        long bookingId = insertStaleBooking(240, BookingStatus.HOLD);
        redisTemplate.opsForValue().set(STOCK_KEY, "9");
        redisTemplate.opsForValue().set(HOLD_KEY, "HOLD", 300, TimeUnit.SECONDS);

        // When
        sweepService.sweepBatch();

        // Then: booking 변경 없음, stock 변경 없음
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(status)
            .as("TTL 미만 → HOLD 유지")
            .isEqualTo("HOLD");
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
            .as("stock 변경 없음")
            .isEqualTo("9");
        assertThat(redisTemplate.hasKey(HOLD_KEY))
            .as("hold key 유지")
            .isTrue();
    }

    // =========================================================================
    // Scenario 4: [edge:failure] booking 이미 COMPLETED → CAS skip (row_count==0)
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("booking 이미 COMPLETED → sweepOne CAS skip + stock 변경 없음")
    void should_skip_sweep_when_booking_already_completed() {
        // Given: booking HOLD 6분 전 후 COMPLETED 직접 UPDATE (Reconciliation 또는 다른 경로 가정)
        long bookingId = insertStaleBooking(360, BookingStatus.HOLD);
        jdbcTemplate.update("UPDATE booking SET status = 'COMPLETED' WHERE id = ?", bookingId);
        redisTemplate.opsForValue().set(STOCK_KEY, "10"); // 이미 COMPLETED 라 stock 정상

        // When
        sweepService.sweepOne(bookingId);

        // Then: 변경 없음 (CAS row_count==0 → skip)
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(status)
            .as("COMPLETED 그대로")
            .isEqualTo("COMPLETED");
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
            .as("stock 변경 없음 — over-INCR 차단")
            .isEqualTo("10");
    }
}

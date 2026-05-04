package com.booking.concurrency;

import com.booking.application.BookingHoldSweepService;
import com.booking.domain.booking.BookingStatus;
import com.booking.integration.IntegrationTestSupport;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency 테스트 — 같은 booking 두 thread 동시 sweepOne CAS 정합성 (Scenario 3).
 *
 * <p>2 thread 가 동시에 같은 booking 의 sweepOne 호출 시 — booking FAILED 1건 (한 thread CAS
 * 통과) + stock INCR 1회 만 (다른 thread 의 CAS row_count==0 → release skip). over-INCR 방지.
 *
 * <p>Source: docs/features/feature-006-ttl-sweeper.md
 */
class BookingHoldSweeperConcurrencyTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        // Spring context cache key 정합 — 다른 IntegrationTest 와 공유 context 사용
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

    private long insertStaleBooking() {
        UUID key = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO booking (user_id, accommodation_id, idempotency_key, amount, " +
                "payment_composition_snapshot, status, created_at, updated_at) " +
                "VALUES (?, ?, UUID_TO_BIN(?), ?, ?, ?, " +
                "DATE_SUB(NOW(), INTERVAL 360 SECOND), DATE_SUB(NOW(), INTERVAL 360 SECOND))",
            1001L, 42L, key.toString(), new BigDecimal("50000.00"),
            "{\"methods\":[]}", BookingStatus.HOLD.name());
        return jdbcTemplate.queryForObject(
            "SELECT id FROM booking ORDER BY id DESC LIMIT 1", Long.class);
    }

    // =========================================================================
    // Scenario 3: [edge:concurrency] 같은 booking 2 thread 동시 sweepOne — CAS 정합성
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:concurrency")
    @DisplayName("같은 booking 2 thread 동시 sweepOne → FAILED 1건 + stock INCR 1회 (over-INCR 차단)")
    void should_handle_concurrent_sweep_with_cas_idempotency() throws Exception {
        // Given: booking HOLD 6분 전 + stock=9 + hold key
        long bookingId = insertStaleBooking();
        redisTemplate.opsForValue().set(STOCK_KEY, "9");
        redisTemplate.opsForValue().set(HOLD_KEY, "HOLD", 300, TimeUnit.SECONDS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger(0);

        // When: 2 thread 동시 sweepOne(bookingId)
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    sweepService.sweepOne(bookingId);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean allDone = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Then: 모든 thread 정상 종료 (errors 0)
        assertThat(allDone).isTrue();
        assertThat(errors.get())
            .as("두 thread 모두 정상 (CAS row_count==0 도 정상 흐름)")
            .isZero();

        // booking FAILED 1건 (단일 row, status 변경)
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
        assertThat(status)
            .as("booking FAILED — CAS 통과 1번")
            .isEqualTo("FAILED");

        // stock INCR 정확히 1회 (9 → 10), over-INCR 차단
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
            .as("stock INCR 1회만 — CAS row_count==0 thread 의 release 호출 X")
            .isEqualTo("10");

        // hold key cleanup
        assertThat(redisTemplate.hasKey(HOLD_KEY)).isFalse();
    }
}

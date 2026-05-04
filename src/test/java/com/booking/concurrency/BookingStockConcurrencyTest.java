package com.booking.concurrency;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.integration.IntegrationTestSupport;
import com.booking.testsupport.BookingTestDataBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency 테스트 — 재고 카운터 (Scenario 4 [edge:concurrency]).
 *
 * <p>100명의 서로 다른 사용자가 각자 고유 idempotency key 를 들고 동시에 POST /booking
 * 을 호출할 때, stock=10 → 정확히 10 success / 90 SOLD_OUT (oversell 0건).
 *
 * <p>test-author Pattern 3 (Lua 동시성). ExecutorService + CountDownLatch +
 * Testcontainers Redis/MySQL (mock 사용 X — 실제 Lua atomic 검증).
 *
 * <p>Source: docs/features/feature-003-stock-counter.md
 */
class BookingStockConcurrencyTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> pgMock.baseUrl());
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String STOCK_KEY = "stock:accommodation:42";
    private static final long PRODUCT_ID = 42L;
    private static final long USER_ID_BASE = 10000L;
    private static final int CONCURRENT_USERS = 100;
    private static final int INITIAL_STOCK = 10;

    @BeforeEach
    void seedStockAndUsers() {
        // base class seedAndCleanFixtures 가 user 1001 만 seed → 100 user 추가 batch INSERT
        // (booking.user_id FK 만족용)
        for (long uid = USER_ID_BASE; uid < USER_ID_BASE + CONCURRENT_USERS; uid++) {
            jdbcTemplate.update("INSERT IGNORE INTO users (id) VALUES (?)", uid);
        }

        // 100 사용자 hold key cleanup
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            redisTemplate.delete("hold:user:" + (USER_ID_BASE + i) + ":product:" + PRODUCT_ID);
        }

        // stock seed
        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(INITIAL_STOCK));
    }

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 4: [edge:concurrency] stock=10 + 100 동시 → 10 success / 90 SOLD_OUT
    // =========================================================================

    // Scenario: [edge:concurrency] 재고 10 + 100 동시 요청 → 정확히 10 success, 90 SOLD_OUT
    // Source: docs/features/feature-003-stock-counter.md
    @Test
    @Tag("edge")
    @Tag("edge:concurrency")
    @DisplayName("stock=10 + 100 동시 요청 → 10 success / 90 SOLD_OUT, oversell 0건")
    void should_oversell_zero_when_100_concurrent_requests_for_stock_10() throws Exception {
        // Given: stock=10, PG 정상 응답 stub (최대 10회 호출 예상)
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-conc\",\"status\":\"SUCCESS\"}")));

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        // When: 100 사용자 (서로 다른 userId + 서로 다른 idempotency key) 동시 요청 발사
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            long userId = USER_ID_BASE + i;
            String key = UUID.randomUUID().toString();
            CreateBookingRequest request = new BookingTestDataBuilder()
                    .withUserId(userId)
                    .buildRequest();
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/booking",
                            buildRequestEntity(key, request),
                            String.class);
                    HttpStatus status = (HttpStatus) response.getStatusCode();
                    if (status == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (status == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        boolean allDone = doneLatch.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        // Then: 정확히 10 success / 90 SOLD_OUT (409), oversell 0건
        assertThat(allDone).as("60초 내 모든 요청 완료").isTrue();
        assertThat(successCount.get())
                .as("정확히 10건 성공 (재고 10개)")
                .isEqualTo(INITIAL_STOCK);
        assertThat(conflictCount.get())
                .as("정확히 90건 SOLD_OUT (409)")
                .isEqualTo(CONCURRENT_USERS - INITIAL_STOCK);
        assertThat(otherCount.get())
                .as("200/409 외 응답 0건")
                .isEqualTo(0);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("최종 재고 0 (oversell 0건 — Lua atomic 검증)")
                .isEqualTo("0");
        // PG 호출 정확히 10회 (재고 통과 후만 PG 호출)
        pgMock.verify(INITIAL_STOCK, postRequestedFor(urlPathMatching("/payment")));
    }
}

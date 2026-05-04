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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency 테스트 — Scenario 5: 동시 동일 키 100건 → 1건 성공, 99건 409.
 * ExecutorService + CountDownLatch + Testcontainers Redis/MySQL (Mockito 사용 X — 실제 환경).
 * test-author.md Pattern 3 (Redis Lua atomic 응용) + Pattern 5 (동시 동일 키).
 * Source: docs/features/feature-001-idempotency-handling.md
 */
class BookingIdempotencyConcurrencyTest extends IntegrationTestSupport {

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

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final int CONCURRENT_REQUESTS = 100;

    @BeforeEach
    void cleanRedis() {
        // Background: 각 테스트 전 Redis 멱등성 키 클린업
        redisTemplate.delete(REDIS_KEY_PREFIX + IDEMPOTENCY_KEY);
    }

    private HttpEntity<CreateBookingRequest> buildRequestEntity(CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", IDEMPOTENCY_KEY);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 5: 동시 동일 키 100건 → 1건만 성공, 99건 409
    // =========================================================================

    // Scenario: [edge:concurrency] 동시 동일 키 100건 → 1건만 성공, 99건 409
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:concurrency")
    @DisplayName("동시 동일 키 100건 → 1건만 성공, 99건 409")
    void should_block_concurrent_same_key_requests() throws Exception {
        // Given: 100 클라이언트가 동일 idempotency_key로 요청 준비
        //        멱등성 키가 Redis와 DB 어디에도 존재하지 않음 (cleanRedis @BeforeEach)
        //        PG 정상 응답 stub (1건만 실제로 통과하므로 1회 호출 예상)
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-conc-001\",\"status\":\"SUCCESS\"}")));

        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);   // 모든 thread 동시 시작 보장
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        // When: 100 클라이언트 동시 요청 발사
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();  // 모든 thread 준비 완료 후 동시 진입
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/booking",
                            buildRequestEntity(request),
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
        startLatch.countDown();  // 동시 출발 신호
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);

        pool.shutdown();

        // Then: 정확히 1건 성공(200), 99건 409
        assertThat(allDone)
                .as("30초 내 모든 요청이 완료되어야 한다")
                .isTrue();
        assertThat(successCount.get())
                .as("동시 100건 중 정확히 1건만 200 성공 (oversell 0건)")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("나머지 99건은 409 Conflict")
                .isEqualTo(99);
        assertThat(otherCount.get())
                .as("200/409 외 다른 응답 없어야 한다")
                .isEqualTo(0);

        // DB booking row 정확히 1건 (Phase 3 구현 후 검증 추가)
        // StockService.decrement() 정확히 1회 (Phase 3 구현 후 검증 추가)
        // PG 호출 정확히 1회
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment")));
    }
}

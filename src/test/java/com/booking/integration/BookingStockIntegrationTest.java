package com.booking.integration;

import com.booking.api.booking.dto.CreateBookingRequest;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — 재고 카운터 (Scenario 1~3).
 * <p>Source: docs/features/feature-003-stock-counter.md
 * <p>ADR-008 amendment 정합 — hold 사용자 단위 5분 TTL.
 */
class BookingStockIntegrationTest extends IntegrationTestSupport {

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

    private static final String STOCK_KEY = "stock:accommodation:42";
    private static final String HOLD_KEY = "hold:user:1001:product:42";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";

    @BeforeEach
    void cleanStockAndHoldKeys() {
        // base class seedAndCleanFixtures (users/accommodation seed) 후 호출됨
        // 본 test class 는 매 시나리오마다 stock 값을 override 하므로 base seed 와 별도로 reset
        redisTemplate.delete(STOCK_KEY);
        redisTemplate.delete(HOLD_KEY);
    }

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 1: [happy] 재고 10 → 첫 진입 → 200 + DECR + hold key 5분 TTL
    // =========================================================================

    // Scenario: [happy] 재고 10 → 첫 진입 → DECR + booking COMPLETED → 200
    // Source: docs/features/feature-003-stock-counter.md
    @Test
    @Tag("happy")
    @DisplayName("재고 10 → 첫 진입 → 200 + DECR 9 + hold key 5분 TTL")
    void should_decrement_stock_and_set_hold_key_when_purchase_succeeds() {
        // Given: stock=10, PG 정상 응답 stub
        redisTemplate.opsForValue().set(STOCK_KEY, "10");
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-stock-001\",\"status\":\"SUCCESS\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then
        assertThat(response.getStatusCode())
                .as("재고 10 → 첫 진입 → 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("응답에 bookingId 포함")
                .contains("bookingId");
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("재고 DECR → 9")
                .isEqualTo("9");
        Long ttl = redisTemplate.getExpire(HOLD_KEY, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("hold key 5분 TTL set (250 < ttl <= 300)")
                .isBetween(250L, 300L);
    }

    // =========================================================================
    // Scenario 2: [edge:boundary] 재고 1 → 진입 → DECR 0
    // =========================================================================

    // Scenario: [edge:boundary] 재고 1 → 진입 성공 → DECR 0
    // Source: docs/features/feature-003-stock-counter.md
    @Test
    @Tag("edge")
    @Tag("edge:boundary")
    @DisplayName("재고 1 → 진입 성공 → DECR 0")
    void should_decrement_stock_to_zero_when_last_one() {
        // Given: stock=1
        redisTemplate.opsForValue().set(STOCK_KEY, "1");
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-stock-002\",\"status\":\"SUCCESS\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then
        assertThat(response.getStatusCode())
                .as("재고 1 → 진입 → 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("재고 1 → 진입 후 0")
                .isEqualTo("0");
    }

    // =========================================================================
    // Scenario 3: [edge:boundary] 재고 0 → SOLD_OUT → 409
    // =========================================================================

    // Scenario: [edge:boundary] 재고 0 → SOLD_OUT → 409
    // Source: docs/features/feature-003-stock-counter.md
    @Test
    @Tag("edge")
    @Tag("edge:boundary")
    @DisplayName("재고 0 → SOLD_OUT → 409 + booking 미생성 + PG 호출 0회")
    void should_return_409_sold_out_when_stock_is_zero() {
        // Given: stock=0 (재고 소진 상태)
        redisTemplate.opsForValue().set(STOCK_KEY, "0");
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"never\",\"status\":\"SUCCESS\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then
        assertThat(response.getStatusCode())
                .as("재고 0 → 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .as("응답에 SOLD_OUT 메시지 포함")
                .contains("SOLD_OUT");
        // PG 호출 발생하지 않아야 함 (재고 카운터 단계에서 차단)
        pgMock.verify(0, postRequestedFor(urlPathMatching("/payment")));
        // idempotency_key Redis 에서 삭제됨 — 클라이언트가 새 키로 재시도 가능
        assertThat(redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + idempotencyKey))
                .as("idempotency key cleanup → 새 키 재시도 허용")
                .isNull();
    }
}

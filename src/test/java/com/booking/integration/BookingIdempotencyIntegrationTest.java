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

import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — POST /booking 멱등성 처리 Scenario 1~4, 6, 7.
 * Testcontainers MySQL 8.0 + Redis 7, WireMock PG mock.
 * Source: docs/features/feature-001-idempotency-handling.md
 */
class BookingIdempotencyIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String REDIS_KEY_PREFIX = "idempotency:";

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

    private HttpEntity<CreateBookingRequest> buildRequestEntityWithKey(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 1: 신규 멱등성 키 → 200 OK + booking 생성
    // =========================================================================

    // Scenario: [happy] 신규 멱등성 키 → 200 OK + booking 생성
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("신규 멱등성 키 → 200 OK + booking 생성")
    void should_create_booking_and_return_200_when_key_is_new() {
        // Given: 멱등성 키가 Redis와 DB 어디에도 존재하지 않고 (cleanRedis @BeforeEach)
        //        PG 정상 응답 stub
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-001\",\"status\":\"SUCCESS\"}")));
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When: 사용자가 멱등성 키와 함께 POST /booking 호출
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(request),
                String.class);

        // Then: HTTP 200 + booking_id 포함 + Redis COMPLETED 갱신 + DB row 생성
        assertThat(response.getStatusCode())
                .as("신규 멱등성 키 → HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("응답 본문에 booking_id 포함")
                .contains("bookingId");
        String redisValue = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + IDEMPOTENCY_KEY);
        assertThat(redisValue)
                .as("Redis 키 상태가 COMPLETED로 갱신")
                .startsWith("COMPLETED:");
    }

    // =========================================================================
    // Scenario 2: 같은 키 + 같은 body, 처리 중 → 409 Conflict
    // =========================================================================

    // Scenario: [happy] 같은 키 + 같은 body, 처리 중 → 409 Conflict
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("같은 키 + 같은 body, 처리 중 → 409 Conflict")
    void should_return_409_when_same_key_in_processing_with_same_body() {
        // Given: 멱등성 키가 Redis에 PROCESSING 상태로 존재하고 body_hash 일치
        //        SHA-256("1001|42|50000.00|CARD|0") — 실제 hash는 production 구현 후 정합
        String bodyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + IDEMPOTENCY_KEY,
                "PROCESSING:" + bodyHash,
                900, TimeUnit.SECONDS);
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When: 사용자가 같은 멱등성 키와 같은 body로 다시 POST /booking 호출
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(request),
                String.class);

        // Then: HTTP 409 Conflict + 재시도 메시지 포함
        assertThat(response.getStatusCode())
                .as("PROCESSING 상태 + 같은 body_hash → 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .as("응답 본문에 재시도 안내 메시지 포함")
                .contains("처리 중");
    }

    // =========================================================================
    // Scenario 3: 같은 키 + 같은 body, 이미 완료 → 200 OK + 캐시 응답
    // =========================================================================

    // Scenario: [happy] 같은 키 + 같은 body, 이미 완료 → 200 OK + 캐시 응답
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("같은 키 + 같은 body, 이미 완료 → 200 OK + 캐시 응답")
    void should_return_cached_response_with_200_when_completed_with_same_body() {
        // Given: 멱등성 키가 Redis에 COMPLETED 상태 + response_payload 캐시됨 + body_hash 일치
        String bodyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String cachedPayload = "{\"bookingId\":999,\"status\":\"COMPLETED\"}";
        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + IDEMPOTENCY_KEY,
                "COMPLETED:" + bodyHash + ":" + cachedPayload,
                900, TimeUnit.SECONDS);
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When: 사용자가 같은 멱등성 키와 같은 body로 다시 POST /booking 호출
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(request),
                String.class);

        // Then: HTTP 200 + 응답 본문이 캐시된 response_payload와 정확히 일치
        assertThat(response.getStatusCode())
                .as("COMPLETED + 같은 body_hash → 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("캐시된 response_payload 그대로 반환")
                .isEqualTo(cachedPayload);
        // 새 booking이 생성되지 않음 — PG mock 호출 횟수 0건 검증
        pgMock.verify(0, postRequestedFor(urlPathMatching("/payment")));
    }

    // =========================================================================
    // Scenario 4: 같은 키 + 다른 body → 422 Unprocessable Entity
    // =========================================================================

    // Scenario: [edge:tampering] 같은 키 + 다른 body → 422 Unprocessable Entity
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("같은 키 + 다른 body → 422 Unprocessable Entity")
    void should_return_422_when_body_hash_differs() {
        // Given: 멱등성 키가 Redis에 어떤 상태로든 존재하고 storedHash != incomingHash
        String storedBodyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + IDEMPOTENCY_KEY,
                "PROCESSING:" + storedBodyHash,
                900, TimeUnit.SECONDS);
        // 다른 body (paymentMethod 변경 → hash 변경)
        CreateBookingRequest tamperedRequest = BookingTestDataBuilder.aTamperedRequest();

        // When: 사용자가 같은 멱등성 키이지만 다른 body로 POST /booking 호출
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(tamperedRequest),
                String.class);

        // Then: HTTP 422 + 충돌 메시지 포함 + 새 booking 미생성
        assertThat(response.getStatusCode())
                .as("body_hash 불일치 → 422 Unprocessable Entity")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .as("응답 본문에 데이터 충돌 메시지 포함")
                .contains("충돌");
        pgMock.verify(0, postRequestedFor(urlPathMatching("/payment")));
    }

    // =========================================================================
    // Scenario 6: TTL 15분 만료 후 같은 키 재시도 → 200 OK (새 결제)
    // =========================================================================

    // Scenario: [edge:expiry] TTL 15분 만료 후 같은 키 재시도 → 200 OK (새 결제)
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:expiry")
    @DisplayName("TTL 15분 만료 후 같은 키 재시도 → 200 OK (새 결제)")
    void should_create_new_booking_when_key_expired_after_15_minutes() {
        // Given: 멱등성 키가 Redis에서 TTL 만료로 제거됨 (cleanRedis @BeforeEach로 보장)
        //        DB idempotency_key의 expires_at도 과거 (Phase 3에서 DB setup 추가)
        //        PG 정상 응답 stub
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-002\",\"status\":\"SUCCESS\"}")));
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When: 사용자가 같은 멱등성 키로 POST /booking 호출 (TTL 만료 후)
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(request),
                String.class);

        // Then: HTTP 200 + 새로운 booking 생성
        assertThat(response.getStatusCode())
                .as("TTL 만료 후 재시도 → 신규 처리로 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("새 booking_id 포함")
                .contains("bookingId");
        // PG 정상 호출 1건 발생
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment")));
    }

    // =========================================================================
    // Scenario 7: Redis 장애 + DB unique constraint가 차단
    // =========================================================================

    // Scenario: [edge:failure] Redis 장애 + DB unique constraint가 차단
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("Redis 장애 + DB unique constraint → 이중 booking 차단")
    void should_block_duplicate_via_db_unique_when_redis_unavailable() {
        // Given: 멱등성 키가 DB idempotency_key 테이블에 COMPLETED로 존재하고
        //        Redis가 장애 상태 (Circuit Breaker OPEN 시뮬레이션)
        //        Phase 3 구현 후: Redis 포트를 닫아 OPEN 상태 유발 또는 Resilience4j 설정으로 강제 OPEN
        //        현재 RED 단계: 테스트는 fail (production 미구현)

        // When: 사용자가 같은 멱등성 키로 POST /booking 호출 (Redis 장애 중)
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(request),
                String.class);

        // Then: ADR-007 Fail-Closed → 503 반환 (Redis 장애 시)
        //       또는 Redis 복구 후 재시도 시 DB unique constraint로 409 반환
        //       이중 booking은 절대 발생하지 않음
        assertThat(response.getStatusCode())
                .as("Redis 장애 시 Fail-Closed → 503 Service Unavailable")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}

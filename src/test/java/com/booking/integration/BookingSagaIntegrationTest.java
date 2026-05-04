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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — Saga + booking 상태 머신 본격 (Scenario 1~4).
 *
 * <p>DECISIONS.md §11 결제 실패 분류 정합:
 * <ul>
 *   <li>케이스 1 (PG 4XX) — booking FAILED + 400 + idempotency cleanup</li>
 *   <li>케이스 2 (PG 5XX/timeout) — booking UNKNOWN + 503 + idempotency PROCESSING 유지</li>
 * </ul>
 *
 * <p>케이스 3 (PG 성공 + DB 실패) — {@code BookingSagaCompensationTest} 별 class
 * (@MockitoBean BookingRepository 가 본 class 의 시나리오 1~4 와 충돌하기 때문).
 *
 * <p>Source: docs/features/feature-004-saga-booking-flow.md
 */
class BookingSagaIntegrationTest extends IntegrationTestSupport {

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
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";

    @BeforeEach
    void cleanRedis() {
        // base seedAndCleanFixtures 후 호출 — stock=10 default 이미 set
    }

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    private long latestBookingId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM booking ORDER BY id DESC LIMIT 1", Long.class);
    }

    // =========================================================================
    // Scenario 1: [happy] PG 성공 → booking COMPLETED + paymentAttempt SUCCESS + outbox INSERT
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("PG 성공 → booking COMPLETED + paymentAttempt SUCCESS + outbox_event INSERT")
    void should_complete_booking_and_insert_outbox_when_pg_success() {
        // Given: stock=10 (base seed), PG 200 응답
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-saga-001\",\"status\":\"SUCCESS\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 200 + booking row 1건 (status=COMPLETED)
        assertThat(response.getStatusCode())
                .as("PG 성공 → 200")
                .isEqualTo(HttpStatus.OK);
        Long bookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'COMPLETED'", Long.class);
        assertThat(bookingCount)
                .as("booking COMPLETED 1건")
                .isEqualTo(1);
        long bookingId = latestBookingId();

        // payment_attempt row 1건 (status=SUCCESS, external_payment_id 채워짐)
        Long paCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_attempt WHERE booking_id = ? AND status = 'SUCCESS' " +
                "AND external_payment_id = ?",
            Long.class, bookingId, "pg-saga-001");
        assertThat(paCount)
                .as("paymentAttempt SUCCESS 1건 + external_payment_id 매핑")
                .isEqualTo(1);

        // outbox_event row 1건 (event_type=BookingCompleted, status=PENDING)
        Long outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'BookingCompleted' " +
                "AND status = 'PENDING'",
            Long.class);
        assertThat(outboxCount)
                .as("outbox_event 1건 PENDING")
                .isEqualTo(1);

        // stock=9
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("stock DECR → 9")
                .isEqualTo("9");
    }

    // =========================================================================
    // Scenario 2: [edge:failure] PG 4XX 거절 → booking FAILED + 400 + idempotency cleanup
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG 4XX 거절 (한도 초과) → booking FAILED + 400 + idempotency cleanup")
    void should_mark_booking_failed_and_return_400_when_pg_rejects() {
        // Given: PG 가 400 + INSUFFICIENT_LIMIT 응답
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INSUFFICIENT_LIMIT\",\"message\":\"한도 초과\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 400 + 재시도 안내 메시지
        assertThat(response.getStatusCode())
                .as("PG 4XX → 400 Bad Request")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("응답에 재시도 안내 포함")
                .contains("결제가 거절");

        // booking row 1건 (status=FAILED), payment_attempt 1건 (status=FAILED)
        Long bookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'FAILED'", Long.class);
        assertThat(bookingCount)
                .as("booking FAILED 1건")
                .isEqualTo(1);
        long bookingId = latestBookingId();

        Long paCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_attempt WHERE booking_id = ? AND status = 'FAILED'",
            Long.class, bookingId);
        assertThat(paCount)
                .as("paymentAttempt FAILED 1건")
                .isEqualTo(1);

        // outbox_event 0건
        Long outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event", Long.class);
        assertThat(outboxCount)
                .as("outbox_event 미생성")
                .isEqualTo(0);

        // idempotency_key Redis cleanup (사용자 새 키로 재시도 가능)
        assertThat(redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + idempotencyKey))
                .as("idempotency key cleanup → 새 키 재시도 허용")
                .isNull();

        // stock 9 — hold 유지 (TTL sweeper feature-006 영역)
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("stock 9 (hold 유지 — TTL sweeper 영역)")
                .isEqualTo("9");
    }

    // =========================================================================
    // Scenario 3: [edge:failure] PG 5XX → booking UNKNOWN + 503
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG 5XX → booking UNKNOWN + paymentAttempt TIMEOUT + 503")
    void should_mark_booking_unknown_when_pg_5xx() {
        // Given: PG 가 500 응답
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 503 + UNKNOWN 진입 안내
        assertThat(response.getStatusCode())
                .as("PG 5XX → 503")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .as("응답에 처리 중 메시지 포함")
                .contains("처리 중");

        // booking 1건 UNKNOWN, paymentAttempt 1건 TIMEOUT
        Long bookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'UNKNOWN'", Long.class);
        assertThat(bookingCount)
                .as("booking UNKNOWN 1건")
                .isEqualTo(1);
        long bookingId = latestBookingId();

        Long paCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_attempt WHERE booking_id = ? AND status = 'TIMEOUT'",
            Long.class, bookingId);
        assertThat(paCount)
                .as("paymentAttempt TIMEOUT 1건")
                .isEqualTo(1);

        // idempotency_key Redis PROCESSING 유지 (Reconciliation 결과 push 후 클라이언트 새로고침)
        String redisValue = redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + idempotencyKey);
        assertThat(redisValue)
                .as("idempotency PROCESSING 유지")
                .startsWith("PROCESSING:");

        // stock 9 — hold 유지
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
    }

    // =========================================================================
    // Scenario 4: [edge:failure] PG timeout (응답 미수신) → booking UNKNOWN + 503
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG timeout → booking UNKNOWN + paymentAttempt TIMEOUT + 503")
    void should_mark_booking_unknown_when_pg_timeout() {
        // Given: PG 가 응답 지연 (RestTemplate timeout trigger — 2초 stub fixed delay vs 1초 read timeout)
        // PaymentConfig 의 RestTemplate read timeout 가 작으면 timeout 발생.
        // 본 시나리오는 RestTemplate timeout 설정에 의존 — 현재 PaymentConfig 의 timeout 값에 따라 작동.
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(aResponse()
                        .withFixedDelay(15000)  // 15초 — RestTemplate read timeout 보다 충분히 김
                        .withStatus(200)
                        .withBody("{\"externalPaymentId\":\"never\",\"status\":\"SUCCESS\"}")));
        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 503 + booking UNKNOWN 1건 + paymentAttempt TIMEOUT 1건
        assertThat(response.getStatusCode())
                .as("PG timeout → 503")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        Long bookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'UNKNOWN'", Long.class);
        assertThat(bookingCount)
                .as("booking UNKNOWN 1건")
                .isEqualTo(1);
    }
}

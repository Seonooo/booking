package com.booking.integration;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import com.booking.testsupport.BookingTestDataBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Integration 테스트 — Saga 보상 본격 (Scenario 4).
 *
 * <p>DECISIONS.md §11 케이스 3 — PG 성공 후 DB 커밋 실패 → SagaCompensationService 호출 →
 * PG cancel API + booking PG_PENDING → FAILED CAS + stock release.
 *
 * <p>기존 BookingSagaCompensationTest (feature-004) 는 *fallback 로깅* 만 검증. 본 PR 의 시나리오는
 * *PG cancel API 호출 1회* + *booking FAILED* + *stock INCR* 까지 본격 검증.
 *
 * <p>Source: docs/features/feature-005-outbox-poller-saga-compensation.md
 */
class BookingSagaCompensationFullIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> pgMock.baseUrl());
    }

    @MockitoBean
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY = "stock:accommodation:42";

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG 성공 + DB 실패 → Saga 보상 (PG cancel + booking FAILED + stock INCR)")
    void should_invoke_pg_cancel_when_db_commit_fails_after_pg_success() {
        // Given: PG /payment SUCCESS + /payment/cancel SUCCESS, IdempotencyKeyRepository.save throw
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-saga-comp\",\"status\":\"SUCCESS\"}")));
        pgMock.stubFor(post(urlPathMatching("/payment/cancel"))
                .willReturn(okJson("{\"status\":\"CANCELLED\"}")));
        doThrow(new DataIntegrityViolationException("mock DB failure after PG success"))
                .when(idempotencyKeyRepository).save(any());

        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 503 + PG cancel 1회 + booking FAILED + stock INCR
        assertThat(response.getStatusCode())
                .as("DB 실패 → 503")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // PG cancel API 1회 호출 — Saga 보상의 핵심 검증
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment/cancel")));

        // booking FAILED 1건 (Saga 보상의 PG_PENDING → FAILED CAS)
        Long failedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'FAILED'", Long.class);
        assertThat(failedCount)
                .as("booking PG_PENDING → FAILED (Saga 보상 CAS)")
                .isEqualTo(1);

        // stock INCR — Saga 보상의 stock release (9 → 10)
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY))
                .as("stock release (Saga 보상)")
                .isEqualTo("10");
    }
}

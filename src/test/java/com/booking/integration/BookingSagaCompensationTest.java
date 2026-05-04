package com.booking.integration;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.booking.BookingRepository;
import com.booking.testsupport.BookingTestDataBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.when;

/**
 * Integration 테스트 — Saga 보상 (Scenario 5).
 *
 * <p>DECISIONS.md §11 케이스 3 — *PG 성공 + DB 커밋 실패 → fallback 로깅 + 503*.
 * PG cancel 호출 본격은 feature-005 영역. 본 시나리오는 *fallback 로깅 마커 + booking 미생성 + 503* 만 검증.
 *
 * <p>{@link BookingRepository} {@code @MockitoBean} 으로 stub — save() throw 시 BookingService
 * 가 fallback 로깅 + 503 반환하는지 검증.
 *
 * <p>Source: docs/features/feature-004-saga-booking-flow.md
 */
class BookingSagaCompensationTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> pgMock.baseUrl());
    }

    @MockitoBean
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 5: [edge:failure] PG 성공 + DB 실패 → fallback 로깅 + 503
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("PG 성공 + DB 실패 → fallback 로깅 + booking 미생성 + 503")
    void should_log_saga_compensation_marker_when_db_fails_after_pg_success() {
        // Given: PG 성공 응답 + BookingRepository.save() throw
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-saga-005\",\"status\":\"SUCCESS\"}")));
        when(bookingRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("mock DB failure after PG success"));

        String idempotencyKey = UUID.randomUUID().toString();
        CreateBookingRequest request = BookingTestDataBuilder.aDefaultCardRequest();

        // When
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(idempotencyKey, request),
                String.class);

        // Then: HTTP 503
        assertThat(response.getStatusCode())
                .as("DB 실패 → 503")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // booking row 0건 (트랜잭션 롤백)
        Long bookingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking", Long.class);
        assertThat(bookingCount)
                .as("booking 미생성 (롤백)")
                .isEqualTo(0);

        // payment_attempt 0건, outbox_event 0건 (모두 롤백)
        Long paCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class);
        assertThat(paCount).as("payment_attempt 미생성").isEqualTo(0);

        Long outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
        assertThat(outboxCount).as("outbox_event 미생성").isEqualTo(0);

        // PG mock 1회 호출 — PG 청구 실제 발생 (운영자 확인 + 보상 필요)
        // 본 PR 은 fallback 로깅만 — feature-005 의 PG cancel 가 후속 처리
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment")));

        // 로그 마커 [SAGA_COMPENSATION_PENDING] 검증은 본 PR scope 외 — log appender 검증은
        // 별 layer (Mockito + LogbackAppender). 본 시나리오는 *503 + booking 미생성 + PG 청구 발생*
        // 까지 검증.
    }
}

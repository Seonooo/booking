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
 * Integration 테스트 — Saga 보상 (Scenario 5).
 *
 * <p>DECISIONS.md §11 케이스 3 — *PG 성공 + DB 커밋 실패 → fallback 로깅 + 503*.
 * PG cancel 호출 본격은 feature-005 영역. 본 시나리오는 *fallback 로깅 마커 + booking 미생성 + 503* 만 검증.
 *
 * <p>{@link IdempotencyKeyRepository} {@code @MockitoBean} 으로 stub — save() throw 시
 * BookingService.finalizeSuccess 트랜잭션 롤백 + 503 반환하는지 검증. mock 위치를
 * BookingRepository 가 아닌 IdempotencyKeyRepository 로 잡은 이유: *PG 호출 후 DB 실패*
 * 시점이 finalizeSuccess 트랜잭션 안의 idempotencyKeyRepository.save 단계 — 이 단계 throw
 * 시 finalizeSuccess 의 paymentAttempt UPDATE / booking CAS / outbox INSERT 모두 롤백.
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
    private IdempotencyKeyRepository idempotencyKeyRepository;

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
    @DisplayName("PG 성공 + DB 실패 → finalizeSuccess 롤백 + booking PG_PENDING + 503")
    void should_log_saga_compensation_marker_when_db_fails_after_pg_success() {
        // Given: PG 성공 응답 + IdempotencyKeyRepository.save() throw (finalizeSuccess 트랜잭션 안)
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-saga-005\",\"status\":\"SUCCESS\"}")));
        doThrow(new DataIntegrityViolationException("mock DB failure after PG success"))
                .when(idempotencyKeyRepository).save(any());

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

        // persistInitialState 트랜잭션은 commit 됨 — booking 1건 PG_PENDING + paymentAttempt 1건 REQUESTED.
        // finalizeSuccess 트랜잭션이 idempotency save 단계에서 throw → 롤백 → COMPLETED 진입 못 함.
        // outbox / idempotency_key DB row 0건. PG 청구 실제 발생.
        // Saga 보상 (PG cancel + booking → FAILED CAS) 은 feature-005 영역.
        Long bookingPgPendingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'PG_PENDING'", Long.class);
        assertThat(bookingPgPendingCount)
                .as("booking PG_PENDING 1건 (persistInitialState commit, finalizeSuccess 롤백)")
                .isEqualTo(1);

        Long paRequestedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_attempt WHERE status = 'REQUESTED'", Long.class);
        assertThat(paRequestedCount)
                .as("paymentAttempt REQUESTED 1건")
                .isEqualTo(1);

        Long outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
        assertThat(outboxCount).as("outbox_event 미생성 (finalizeSuccess 롤백)").isEqualTo(0);

        // PG mock 1회 호출 — PG 청구 실제 발생 (운영자 확인 + 보상 필요, feature-005)
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment")));
    }
}

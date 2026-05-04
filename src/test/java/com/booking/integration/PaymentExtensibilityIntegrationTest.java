package com.booking.integration;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.testsupport.BookingTestDataBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — 결제 수단 확장성 (REQUIREMENTS Req 4 / ADR-009).
 *
 * <p>Y페이 + 포인트 결제 추가 + 복합 결제 + 지원하지 않는 결제 수단 → 400.
 *
 * <p>Source: docs/features/feature-008-payment-extensibility.md
 */
class PaymentExtensibilityIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static WireMockExtension pgMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(0))
            .build();

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> pgMock.baseUrl());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpEntity<CreateBookingRequest> buildRequestEntity(String key, CreateBookingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Scenario 1: [happy] CARD 결제 → 회귀 검증
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("CARD 결제 → 200 + PG /payment 호출 1회 (회귀)")
    void should_process_card_payment() {
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-card\",\"status\":\"SUCCESS\"}")));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(UUID.randomUUID().toString(), BookingTestDataBuilder.aDefaultCardRequest()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment")));
    }

    // =========================================================================
    // Scenario 2: [happy] YPAY 결제 → YPayPayment.execute
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("YPAY 결제 → 200 + PG /ypay/payment 호출 1회")
    void should_process_ypay_payment() {
        pgMock.stubFor(post(urlPathMatching("/ypay/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-ypay\",\"status\":\"SUCCESS\"}")));

        CreateBookingRequest request = new com.booking.testsupport.BookingTestDataBuilder()
                .withPaymentMethod("YPAY")
                .buildRequest();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(UUID.randomUUID().toString(), request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        pgMock.verify(1, postRequestedFor(urlPathMatching("/ypay/payment")));
        // CARD 측 PG 호출 0
        pgMock.verify(0, postRequestedFor(urlPathMatching("/payment\\b")));
    }

    // =========================================================================
    // Scenario 3: [happy] CARD + POINT 복합 결제
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("CARD + POINT 5000 → 200 + PG /payment amount=45000 + PointPayment 호출")
    void should_process_card_plus_point_composition() {
        pgMock.stubFor(post(urlPathMatching("/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-card-point\",\"status\":\"SUCCESS\"}")));

        CreateBookingRequest request = new com.booking.testsupport.BookingTestDataBuilder()
                .withAmount(new BigDecimal("50000.00"))
                .withPaymentMethod("CARD")
                .withPoints(5000L)
                .buildRequest();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(UUID.randomUUID().toString(), request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // PG external amount = 50000.00 - 5000 = 45000.00 (BigDecimal scale 보존)
        pgMock.verify(1, postRequestedFor(urlPathMatching("/payment"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("45000.00"))));
        // PointPayment 호출 검증은 간접 — booking COMPLETED + PG amount=45000 정확
        Long completedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking WHERE status = 'COMPLETED'", Long.class);
        assertThat(completedCount).isEqualTo(1);
    }

    // =========================================================================
    // Scenario 4: [happy] YPAY + POINT 복합 결제
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("YPAY + POINT 10000 → 200 + PG /ypay/payment amount=40000 + PointPayment 호출")
    void should_process_ypay_plus_point_composition() {
        pgMock.stubFor(post(urlPathMatching("/ypay/payment"))
                .willReturn(okJson("{\"externalPaymentId\":\"pg-ypay-point\",\"status\":\"SUCCESS\"}")));

        CreateBookingRequest request = new com.booking.testsupport.BookingTestDataBuilder()
                .withAmount(new BigDecimal("50000.00"))
                .withPaymentMethod("YPAY")
                .withPoints(10000L)
                .buildRequest();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(UUID.randomUUID().toString(), request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // PG external amount = 50000.00 - 10000 = 40000.00 (BigDecimal scale 보존)
        pgMock.verify(1, postRequestedFor(urlPathMatching("/ypay/payment"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("40000.00"))));
    }

    // =========================================================================
    // Scenario 5: [edge:tampering] 지원하지 않는 결제 수단 → 400
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("paymentMethod=UNKNOWN_METHOD → 400 (지원하지 않는 결제 수단)")
    void should_return_400_when_payment_method_unsupported() {
        CreateBookingRequest request = new com.booking.testsupport.BookingTestDataBuilder()
                .withPaymentMethod("UNKNOWN_METHOD")
                .buildRequest();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/booking",
                buildRequestEntity(UUID.randomUUID().toString(), request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("응답에 지원하지 않는 결제 수단 메시지 포함")
                .contains("지원하지 않는 결제 수단");
        // PG 호출 발생하지 않아야 함
        pgMock.verify(0, postRequestedFor(urlPathMatching("/payment.*")));
    }
}

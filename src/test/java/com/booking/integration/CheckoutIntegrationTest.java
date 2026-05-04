package com.booking.integration;

import com.booking.api.checkout.dto.CheckoutResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — GET /checkout (REQUIREMENTS §1.1, feature-002 minimal MVP).
 *
 * <p>본 PR 은 main 기반 — 별도 {@code IntegrationTestSupport} (PR #10 영역) 미반영
 * 상태이므로 standalone Testcontainers setup 사용. PR #10 merge 후 후속 통합
 * 검토 시 base class 로 통일 검토.
 *
 * <p>Source: docs/features/feature-002-checkout-api.md
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class CheckoutIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("booking_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long USER_ID = 1001L;
    private static final long PRODUCT_ID = 42L;

    @BeforeEach
    void seedAccommodation() {
        // FK 의존성 없이 accommodation 단독 INSERT — 본 feature read-only 라
        // booking / users 등 다른 테이블 cleanup 무관
        jdbcTemplate.execute("DELETE FROM accommodation");
        jdbcTemplate.update(
            "INSERT INTO accommodation (id, name, base_price) VALUES (?, ?, ?)",
            PRODUCT_ID, "테스트 숙소", new BigDecimal("50000.00"));
    }

    // Scenario: [happy] 존재하는 상품 조회 → 200 + 상품 정보 + 가용 포인트
    // Source: docs/features/feature-002-checkout-api.md
    @Test
    @Tag("happy")
    @DisplayName("존재하는 상품 조회 → 200 + 상품 정보 + 가용 포인트")
    void should_return_200_with_accommodation_when_product_exists() {
        // When
        ResponseEntity<CheckoutResponse> response = restTemplate.getForEntity(
            "/checkout?productId=" + PRODUCT_ID + "&userId=" + USER_ID,
            CheckoutResponse.class);

        // Then
        assertThat(response.getStatusCode())
            .as("존재 상품 → 200 OK")
            .isEqualTo(HttpStatus.OK);
        CheckoutResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.productId()).isEqualTo(PRODUCT_ID);
        assertThat(body.name()).isEqualTo("테스트 숙소");
        assertThat(body.basePrice()).isEqualByComparingTo("50000.00");
        assertThat(body.checkInTime()).isEqualTo("15:00");
        assertThat(body.checkOutTime()).isEqualTo("11:00");
        assertThat(body.availablePoints())
            .as("placeholder 0 — point 도메인 future feature")
            .isEqualTo(0L);
    }

    // Scenario: [edge:tampering] 존재하지 않는 상품 조회 → 404
    // Source: docs/features/feature-002-checkout-api.md
    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("존재하지 않는 상품 조회 → 404")
    void should_return_404_when_product_does_not_exist() {
        // Given: productId 99 미존재 (seedAccommodation 이 42만 삽입)
        long missingProductId = 99L;

        // When
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/checkout?productId=" + missingProductId + "&userId=" + USER_ID,
            String.class);

        // Then
        assertThat(response.getStatusCode())
            .as("미존재 상품 → 404")
            .isEqualTo(HttpStatus.NOT_FOUND);
    }
}

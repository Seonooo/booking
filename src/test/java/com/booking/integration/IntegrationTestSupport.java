package com.booking.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration / Concurrency 테스트가 공유하는 Testcontainers base class.
 * test-author.md Pattern 1 — @DynamicPropertySource로 동적 포트 주입.
 *
 * anti-pattern 방지:
 * - application-test.yml에 정적 JDBC URL 하드코딩 금지 (Testcontainers 동적 포트와 충돌)
 * - @DynamicPropertySource 누락 금지 (spring.datasource.url이 production 값으로 fallback)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

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
        // Flyway: Testcontainers MySQL에서 마이그레이션 실행
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 본 PR 의 booking row 가 FK 만족하도록 users(1001) + accommodation(42)
     * 사전 INSERT. 매 테스트 전에 booking / idempotency_key 도 cleanup —
     * Testcontainers 컨테이너는 재사용 (`.withReuse(true)`) 이라 row 격리가
     * 컨테이너 단위로 보장되지 않는다.
     *
     * <p>feature-003: stock counter 도입 후 Redis stock 카운터 / hold key 도 매
     * 테스트 전 reset (default stock=10 — sub class 가 시나리오별 override 가능).
     */
    @BeforeEach
    void seedAndCleanFixtures() {
        // 의존성 역순 cleanup (FK 무결성 보존)
        jdbcTemplate.execute("DELETE FROM idempotency_key");
        jdbcTemplate.execute("DELETE FROM booking");
        jdbcTemplate.execute("DELETE FROM accommodation");
        jdbcTemplate.execute("DELETE FROM users");

        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", 1001L);
        jdbcTemplate.update(
            "INSERT INTO accommodation (id, name, base_price) VALUES (?, ?, ?)",
            42L, "테스트 숙소", new java.math.BigDecimal("50000.00"));

        // Redis stock seed (default 10) + hold key cleanup
        redisTemplate.opsForValue().set("stock:accommodation:42", "10");
        redisTemplate.delete("hold:user:1001:product:42");
    }
}

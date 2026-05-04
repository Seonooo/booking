package com.booking.idempotency;

import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyStatus;
import com.booking.infrastructure.persistence.IdempotencyKeyJpaEntity;
import com.booking.infrastructure.persistence.IdempotencyKeyJpaRepository;
import com.booking.infrastructure.persistence.IdempotencyKeyRepositoryAdapter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test — {@link IdempotencyKeyJpaRepository} + {@link IdempotencyKeyRepositoryAdapter}
 * 검증. Testcontainers MySQL 8.0, Flyway V1__init.sql 자동 적용.
 *
 * <p>test-author.md Pattern 1 @DynamicPropertySource 패턴 적용.
 * @DataJpaTest + @AutoConfigureTestDatabase(replace = NONE) 으로 H2 우회 → 실 MySQL 강제.
 *
 * <p>Source: docs/features/feature-001-idempotency-handling.md
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IdempotencyKeyRepositoryAdapter.class)
@Testcontainers
@ActiveProfiles("test")
class IdempotencyKeyJpaRepositoryTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("booking_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // Flyway: Testcontainers MySQL에서 마이그레이션 실행
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);
    }

    @Autowired
    private IdempotencyKeyJpaRepository jpaRepository;

    @Autowired
    private IdempotencyKeyRepositoryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final long USER_ID = 1001L;
    private static final String HASH = "a".repeat(64);

    // -------------------------------------------------------------------
    // helper — valid IdempotencyKey 도메인 객체 생성
    // -------------------------------------------------------------------

    private static IdempotencyKey aProcessingKey(UUID key) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new IdempotencyKey(
                key, USER_ID, HASH,
                IdempotencyStatus.PROCESSING,
                null, null,
                now,
                now.plus(15, ChronoUnit.MINUTES));
    }

    /**
     * FK 제약 충족을 위해 users → accommodation → booking 최소 row 삽입.
     * booking.idempotency_key 는 UNIQUE — 각 테스트마다 별도 UUID 사용.
     *
     * @return 삽입된 booking row 의 auto-increment id
     */
    private long insertParentBookingRow() {
        jdbcTemplate.execute("INSERT IGNORE INTO users (id) VALUES (1001)");
        jdbcTemplate.execute(
                "INSERT IGNORE INTO accommodation (id, name, base_price) VALUES (42, 'Test', 100.00)");
        // booking.idempotency_key UNIQUE → 매 호출마다 UUID 새로 생성
        byte[] randomIk = uuidToBytes(UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO booking (user_id, accommodation_id, idempotency_key, amount, "
                        + "payment_composition_snapshot, status) VALUES (1001, 42, ?, 50000.00, '{}', 'COMPLETED')",
                randomIk);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** UUID → BINARY(16) 바이트 배열 변환 (MySQL BINARY(16) 저장용). */
    private static byte[] uuidToBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (msb & 0xFF);
            msb >>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            bytes[i] = (byte) (lsb & 0xFF);
            lsb >>= 8;
        }
        return bytes;
    }

    // =========================================================================
    // Scenario 1: save → findById 왕복 + toDomain() 정합
    // =========================================================================

    // Scenario: [happy] save 후 findById, toDomain() 정합 검증
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("save 후 findById → toDomain() 모든 필드 정합")
    void should_persist_and_find_by_id_when_save_then_findById() {
        // Given: 신규 IdempotencyKey 도메인 객체
        UUID key = UUID.randomUUID();
        IdempotencyKey domainKey = aProcessingKey(key);

        // When: adapter.save (도메인 → entity 변환 + JPA persist)
        adapter.save(domainKey);

        // Then: adapter.findById 로 재조회 → 모든 필드 일치
        Optional<IdempotencyKey> found = adapter.findById(key);
        assertThat(found)
                .as("저장된 키가 Optional로 반환되어야 함")
                .isPresent();

        IdempotencyKey loaded = found.get();
        assertThat(loaded.getIdempotencyKey())
                .as("idempotencyKey UUID 일치")
                .isEqualTo(key);
        assertThat(loaded.getUserId())
                .as("userId 일치")
                .isEqualTo(USER_ID);
        assertThat(loaded.getBodyHash())
                .as("bodyHash 일치")
                .isEqualTo(HASH);
        assertThat(loaded.getStatus())
                .as("status PROCESSING 일치")
                .isEqualTo(IdempotencyStatus.PROCESSING);
        assertThat(loaded.getResponsePayload())
                .as("PROCESSING 상태 → responsePayload null")
                .isNull();
        assertThat(loaded.getBookingId())
                .as("PROCESSING 상태 → bookingId null")
                .isNull();
        assertThat(loaded.getCreatedAt())
                .as("createdAt 일치")
                .isEqualTo(domainKey.getCreatedAt());
        assertThat(loaded.getExpiresAt())
                .as("expiresAt 일치")
                .isEqualTo(domainKey.getExpiresAt());
    }

    // =========================================================================
    // Scenario 2: 중복 PK INSERT → DataIntegrityViolationException (ADR-006 DB 2차 방어선)
    // =========================================================================

    // Scenario: [edge:tampering] 같은 idempotencyKey 중복 INSERT → DataIntegrityViolationException
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("같은 idempotencyKey 중복 INSERT → DataIntegrityViolationException (DB 2차 방어선)")
    void should_throw_DataIntegrityViolation_when_save_with_duplicate_PK() {
        // Given: 키 최초 저장 성공
        UUID key = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        IdempotencyKeyJpaEntity first = IdempotencyKeyJpaEntity.fromDomain(
                new IdempotencyKey(key, USER_ID, HASH, IdempotencyStatus.PROCESSING,
                        null, null, now, now.plus(15, ChronoUnit.MINUTES)));
        entityManager.persist(first);
        entityManager.flush();

        // When + Then: JPA 세션 우회 — 동일 BINARY(16) PK 로 raw JDBC INSERT 시도
        // → DB PRIMARY KEY 제약 위반 → Spring DataIntegrityViolationException
        byte[] pkBytes = uuidToBytes(key);
        String duplicateHash = "b".repeat(64);
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO idempotency_key "
                                + "(idempotency_key, user_id, body_hash, status, created_at, expires_at) "
                                + "VALUES (?, ?, ?, 'PROCESSING', NOW(), DATE_ADD(NOW(), INTERVAL 15 MINUTE))",
                        pkBytes, USER_ID, duplicateHash))
                .as("동일 BINARY(16) PK 재삽입 → DataIntegrityViolationException (DB PRIMARY KEY 위반)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // Scenario 3: updateToCompleted → status/payload/bookingId 반영
    // =========================================================================

    // Scenario: [happy] PROCESSING → updateToCompleted → COMPLETED + responsePayload + bookingId
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("PROCESSING 저장 후 updateToCompleted → findById 시 COMPLETED + responsePayload + bookingId")
    void should_update_status_to_COMPLETED_when_updateToCompleted_called() {
        // Given: PROCESSING 상태로 먼저 저장
        //        FK fk_ik_booking 충족을 위해 booking row 먼저 삽입
        long validBookingId = insertParentBookingRow();
        UUID key = UUID.randomUUID();
        adapter.save(aProcessingKey(key));

        String responsePayload = "{\"bookingId\":" + validBookingId + ",\"status\":\"COMPLETED\"}";

        // When: updateToCompleted 호출 (내부 @Transactional)
        adapter.updateToCompleted(key, responsePayload, validBookingId);

        // Then: findById 재조회 시 COMPLETED + 필드 반영
        // @Modifying 쿼리 캐시 무효화를 위해 EntityManager clear 후 조회
        entityManager.clear();
        Optional<IdempotencyKey> updated = adapter.findById(key);
        assertThat(updated).isPresent();

        IdempotencyKey loaded = updated.get();
        assertThat(loaded.getStatus())
                .as("상태가 COMPLETED로 전이")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        // MySQL JSON 컬럼은 key 정렬을 정규화할 수 있음 → 정확한 문자열 대신
        // 필드 포함 여부로 검증 (semantic equality)
        assertThat(loaded.getResponsePayload())
                .as("responsePayload 저장 — bookingId 포함")
                .contains("bookingId")
                .contains("COMPLETED");
        assertThat(loaded.getBookingId())
                .as("bookingId 저장")
                .isEqualTo(validBookingId);
    }

    // =========================================================================
    // Scenario 4: 없는 UUID 조회 → Optional.empty()
    // =========================================================================

    // Scenario: [happy] findById — 존재하지 않는 키 → Optional.empty()
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("존재하지 않는 UUID 조회 → Optional.empty()")
    void should_return_empty_when_findById_with_unknown_key() {
        // Given: 저장되지 않은 UUID
        UUID unknownKey = UUID.randomUUID();

        // When: adapter.findById
        Optional<IdempotencyKey> result = adapter.findById(unknownKey);

        // Then: Optional.empty()
        assertThat(result)
                .as("없는 UUID → Optional.empty()")
                .isEmpty();
    }

    // =========================================================================
    // Scenario 5: response_payload JSON 컬럼 — 긴 JSON 정합
    // =========================================================================

    // Scenario: [happy] response_payload JSON 컬럼에 긴 JSON 저장 → 읽기 정합
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("response_payload JSON 컬럼 — 긴 JSON string save/read 정합")
    void should_persist_response_payload_as_JSON_column() {
        // Given: PROCESSING 키 저장 후 긴 JSON payload로 COMPLETED 전이
        //        FK fk_ik_booking 충족을 위해 booking row 먼저 삽입
        long validBookingId = insertParentBookingRow();
        UUID key = UUID.randomUUID();
        adapter.save(aProcessingKey(key));

        // 복잡한 중첩 JSON (MySQL JSON 컬럼이 검증하는 well-formed 포함)
        String longJson = "{\"bookingId\":" + validBookingId + ",\"status\":\"COMPLETED\","
                + "\"amount\":50000.00,\"paymentMethod\":\"CARD\",\"accommodationId\":42,"
                + "\"extra\":{\"pgRef\":\"pg-ref-00000000\",\"approvedAt\":\"2026-05-03T00:00:00Z\"}}";

        // When: updateToCompleted
        adapter.updateToCompleted(key, longJson, validBookingId);

        // Then: 읽어온 responsePayload가 핵심 필드를 포함 (MySQL JSON 컬럼은 key 정렬 정규화)
        entityManager.clear();
        IdempotencyKey loaded = adapter.findById(key).orElseThrow();
        assertThat(loaded.getResponsePayload())
                .as("MySQL JSON 컬럼 — 응답이 null 이 아님")
                .isNotNull()
                .as("bookingId 필드 포함")
                .contains("bookingId")
                .as("COMPLETED status 포함")
                .contains("COMPLETED")
                .as("pgRef 포함 (중첩 JSON)")
                .contains("pg-ref-00000000")
                .as("approvedAt 포함 (중첩 JSON)")
                .contains("2026-05-03T00:00:00Z");
    }

    // =========================================================================
    // Scenario 6: BINARY(16) UUID PK roundtrip 정합
    // =========================================================================

    // Scenario: [happy] BINARY(16) UUID PK — write/read 동일 UUID 보장
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("BINARY(16) UUID PK roundtrip — write/read 동일 UUID")
    void should_preserve_BINARY_16_uuid_roundtrip() {
        // Given: 특정 UUID (모든 비트 패턴 포함)
        UUID key = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        adapter.save(aProcessingKey(key));

        // When: findById
        Optional<IdempotencyKey> found = adapter.findById(key);

        // Then: 반환된 UUID가 원본과 bit-for-bit 동일
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey())
                .as("BINARY(16) UUID roundtrip — MSB/LSB 보존")
                .isEqualTo(key);
        assertThat(found.get().getIdempotencyKey().toString())
                .as("UUID 문자열 표현 일치")
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }
}

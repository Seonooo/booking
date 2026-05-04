package com.booking.idempotency;

import com.booking.application.BodyHashCalculator;
import com.booking.application.IdempotencyCheckResult;
import com.booking.application.IdempotencyCheckResult.ResultType;
import com.booking.application.IdempotencyKeyService;
import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import com.booking.domain.idempotency.IdempotencyStatus;
import com.booking.infrastructure.redis.IdempotencyLuaScript;
import com.booking.infrastructure.redis.RedisUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit 테스트 — BodyHashCalculator 정확성 + IdempotencyKeyService 흐름.
 *
 * <p>Service 의존성: {@link IdempotencyLuaScript} (Redis 1차) +
 * {@link IdempotencyKeyRepository} (DB 2차). Mockito 로 둘 다 격리.
 *
 * <p>그룹 구성:
 * <ul>
 *   <li>{@code BodyHashCalculatorTests} — Service 무관, 순수 helper 검증</li>
 *   <li>{@code RedisHappyPath} — Lua 정상 응답을 Service 가 그대로 반환</li>
 *   <li>{@code DbFallbackBranch} — Redis 장애 시 Repository 기반 DB 2차 분기</li>
 * </ul>
 *
 * <p>Source: docs/features/feature-001-idempotency-handling.md
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyKeyServiceTest {

    @Mock
    private IdempotencyLuaScript luaScript;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @InjectMocks
    private IdempotencyKeyService idempotencyKeyService;

    private static final UUID TEST_KEY = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final long USER_ID = 1001L;
    private static final long PRODUCT_ID = 42L;
    private static final BigDecimal AMOUNT = new BigDecimal("50000.00");

    // =========================================================================
    // BodyHashCalculator 단위 검증 (ADR-006 §5)
    // =========================================================================

    @Nested
    @DisplayName("BodyHashCalculator")
    class BodyHashCalculatorTests {

        private final BodyHashCalculator bodyHashCalculator = new BodyHashCalculator();

        // Scenario: body_hash는 SHA-256(userId|productId|amount|paymentMethod|points) 64자 hex
        @Test
        @Tag("happy")
        @DisplayName("동일 입력에 동일 64자 hex 반환")
        void should_return_same_64char_hex_when_same_inputs() {
            CreateBookingRequest request1 = new CreateBookingRequest(USER_ID, PRODUCT_ID, AMOUNT, "CARD", 0L);
            CreateBookingRequest request2 = new CreateBookingRequest(USER_ID, PRODUCT_ID, AMOUNT, "CARD", 0L);

            String hash1 = bodyHashCalculator.calculate(request1);
            String hash2 = bodyHashCalculator.calculate(request2);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @Tag("happy")
        @DisplayName("paymentMethod 대소문자 정규화 후 동일 hash")
        void should_return_same_hash_when_payment_method_case_differs() {
            CreateBookingRequest lower = new CreateBookingRequest(USER_ID, PRODUCT_ID, AMOUNT, "card", 0L);
            CreateBookingRequest upper = new CreateBookingRequest(USER_ID, PRODUCT_ID, AMOUNT, "CARD", 0L);

            assertThat(bodyHashCalculator.calculate(lower))
                .isEqualTo(bodyHashCalculator.calculate(upper));
        }

        @Test
        @Tag("edge")
        @Tag("edge:tampering")
        @DisplayName("필드 하나 변경 시 다른 hash (| 구분자 ambiguity 방지)")
        void should_return_different_hash_when_any_field_changes() {
            CreateBookingRequest original = new CreateBookingRequest(USER_ID, PRODUCT_ID, AMOUNT, "CARD", 0L);
            CreateBookingRequest differentProduct = new CreateBookingRequest(USER_ID, 99L, AMOUNT, "CARD", 0L);
            CreateBookingRequest ambig1 = new CreateBookingRequest(1L, 23L, AMOUNT, "CARD", 0L);
            CreateBookingRequest ambig2 = new CreateBookingRequest(12L, 3L, AMOUNT, "CARD", 0L);

            assertThat(bodyHashCalculator.calculate(original))
                .isNotEqualTo(bodyHashCalculator.calculate(differentProduct));
            assertThat(bodyHashCalculator.calculate(ambig1))
                .isNotEqualTo(bodyHashCalculator.calculate(ambig2));
        }

        @Test
        @Tag("happy")
        @DisplayName("amount toPlainString() 호출로 64자 hex 출력")
        void should_produce_64char_hex_for_any_BigDecimal_representation() {
            CreateBookingRequest req1 = new CreateBookingRequest(USER_ID, PRODUCT_ID, new BigDecimal("50000.00"), "CARD", 0L);
            CreateBookingRequest req2 = new CreateBookingRequest(USER_ID, PRODUCT_ID, new BigDecimal("50000"), "CARD", 0L);

            assertThat(bodyHashCalculator.calculate(req1)).hasSize(64);
            assertThat(bodyHashCalculator.calculate(req2)).hasSize(64);
        }
    }

    // =========================================================================
    // checkAndReserve — Redis 정상 (Lua 결과 위임)
    // =========================================================================

    @Nested
    @DisplayName("checkAndReserve — Redis 정상 → Lua 결과 그대로 반환")
    class RedisHappyPath {

        @Test
        @Tag("happy")
        @DisplayName("Lua 가 NEW 반환 → Service 도 NEW (Repository 미호출)")
        void should_return_NEW_when_lua_returns_NEW() {
            String hash = "a".repeat(64);
            when(luaScript.execute(eq(TEST_KEY), eq(hash)))
                .thenReturn(new IdempotencyCheckResult(ResultType.NEW, null));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.NEW);
            verify(idempotencyKeyRepository, never()).findById(TEST_KEY);
        }

        @Test
        @Tag("happy")
        @DisplayName("Lua 가 PROCESSING 반환 → Service 도 PROCESSING")
        void should_return_PROCESSING_when_lua_returns_PROCESSING() {
            String hash = "b".repeat(64);
            when(luaScript.execute(eq(TEST_KEY), eq(hash)))
                .thenReturn(new IdempotencyCheckResult(ResultType.PROCESSING, null));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.PROCESSING);
            verify(idempotencyKeyRepository, never()).findById(TEST_KEY);
        }

        @Test
        @Tag("happy")
        @DisplayName("Lua 가 COMPLETED+cached 반환 → Service 도 그대로")
        void should_return_COMPLETED_with_cached_when_lua_returns_COMPLETED() {
            String hash = "c".repeat(64);
            String cached = "{\"bookingId\":999}";
            when(luaScript.execute(eq(TEST_KEY), eq(hash)))
                .thenReturn(new IdempotencyCheckResult(ResultType.COMPLETED, cached));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.COMPLETED);
            assertThat(result.cachedResponse()).isEqualTo(cached);
        }

        @Test
        @Tag("edge")
        @Tag("edge:tampering")
        @DisplayName("Lua 가 HASH_MISMATCH 반환 → Service 도 HASH_MISMATCH")
        void should_return_HASH_MISMATCH_when_lua_returns_HASH_MISMATCH() {
            String hash = "d".repeat(64);
            when(luaScript.execute(eq(TEST_KEY), eq(hash)))
                .thenReturn(new IdempotencyCheckResult(ResultType.HASH_MISMATCH, null));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.HASH_MISMATCH);
        }
    }

    // =========================================================================
    // checkAndReserve — Redis 장애 → DB 2차 fallback
    // =========================================================================

    @Nested
    @DisplayName("checkAndReserve — Redis 장애 → Repository 기반 DB 2차 분기")
    class DbFallbackBranch {

        @BeforeEach
        void redisDown() {
            when(luaScript.execute(eq(TEST_KEY), anyString()))
                .thenThrow(new RedisUnavailableException("CB OPEN", null));
        }

        @Test
        @Tag("edge")
        @Tag("edge:failure")
        @DisplayName("DB 에 키 없음 → NEW")
        void should_return_NEW_when_db_finds_no_key() {
            when(idempotencyKeyRepository.findById(TEST_KEY)).thenReturn(Optional.empty());

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, "a".repeat(64));

            assertThat(result.type()).isEqualTo(ResultType.NEW);
            assertThat(result.cachedResponse()).isNull();
        }

        @Test
        @Tag("edge")
        @Tag("edge:failure")
        @DisplayName("DB 에 PROCESSING + 같은 hash → PROCESSING")
        void should_return_PROCESSING_when_db_has_processing_with_same_hash() {
            String hash = "b".repeat(64);
            IdempotencyKey existing = new IdempotencyKey(
                TEST_KEY, USER_ID, hash, IdempotencyStatus.PROCESSING,
                null, null, Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
            when(idempotencyKeyRepository.findById(TEST_KEY)).thenReturn(Optional.of(existing));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.PROCESSING);
        }

        @Test
        @Tag("edge")
        @Tag("edge:failure")
        @DisplayName("DB 에 COMPLETED + 같은 hash → COMPLETED + cachedResponse")
        void should_return_COMPLETED_with_cached_when_db_has_completed_with_same_hash() {
            String hash = "c".repeat(64);
            String cached = "{\"bookingId\":999,\"status\":\"COMPLETED\"}";
            IdempotencyKey existing = new IdempotencyKey(
                TEST_KEY, USER_ID, hash, IdempotencyStatus.COMPLETED,
                cached, 999L, Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
            when(idempotencyKeyRepository.findById(TEST_KEY)).thenReturn(Optional.of(existing));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.COMPLETED);
            assertThat(result.cachedResponse()).isEqualTo(cached);
        }

        @Test
        @Tag("edge")
        @Tag("edge:tampering")
        @DisplayName("DB 에 다른 hash → HASH_MISMATCH (DB 2차 방어선이 차단)")
        void should_return_HASH_MISMATCH_when_db_has_different_hash() {
            String stored = "d".repeat(64);
            String incoming = "e".repeat(64);
            IdempotencyKey existing = new IdempotencyKey(
                TEST_KEY, USER_ID, stored, IdempotencyStatus.PROCESSING,
                null, null, Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
            when(idempotencyKeyRepository.findById(TEST_KEY)).thenReturn(Optional.of(existing));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, incoming);

            assertThat(result.type()).isEqualTo(ResultType.HASH_MISMATCH);
        }

        @Test
        @Tag("edge")
        @Tag("edge:expiry")
        @DisplayName("DB 에 expired 키 → NEW (만료 키는 새 결제)")
        void should_return_NEW_when_db_has_expired_key() {
            String hash = "f".repeat(64);
            IdempotencyKey expired = new IdempotencyKey(
                TEST_KEY, USER_ID, hash, IdempotencyStatus.PROCESSING,
                null, null,
                Instant.now().minus(16, ChronoUnit.MINUTES),
                Instant.now().minus(1, ChronoUnit.MINUTES));
            when(idempotencyKeyRepository.findById(TEST_KEY)).thenReturn(Optional.of(expired));

            IdempotencyCheckResult result = idempotencyKeyService.checkAndReserve(TEST_KEY, hash);

            assertThat(result.type()).isEqualTo(ResultType.NEW);
        }
    }
}

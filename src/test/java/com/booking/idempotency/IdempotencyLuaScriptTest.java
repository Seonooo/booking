package com.booking.idempotency;

import com.booking.application.IdempotencyCheckResult;
import com.booking.application.IdempotencyCheckResult.ResultType;
import com.booking.infrastructure.redis.IdempotencyLuaScript;
import com.booking.integration.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test — {@link IdempotencyLuaScript} Lua atomic 동작 검증.
 * Testcontainers Redis 7-alpine (real Redis 필수 — Lua atomicity 보장).
 *
 * <p>test-author.md Pattern 3 (Redis Lua Atomic Concurrency) 참조.
 * IntegrationTestSupport extend 으로 컨테이너 재사용 + Spring full context.
 *
 * <p>Source: docs/features/feature-001-idempotency-handling.md
 */
class IdempotencyLuaScriptTest extends IntegrationTestSupport {

    private static final String KEY_PREFIX = "idempotency:";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private IdempotencyLuaScript luaScript;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private UUID testKey;

    @BeforeEach
    void setUp() {
        testKey = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        // 매 테스트 후 Redis 키 cleanup — 단일 컨테이너 격리
        redisTemplate.delete(KEY_PREFIX + testKey);
    }

    // =========================================================================
    // Scenario 1: 신규 키 → NEW + Redis PROCESSING 값 설정
    // =========================================================================

    // Scenario: [happy] 신규 키 → NEW 반환 + Redis PROCESSING:{hash} 설정 확인
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("신규 키 → NEW 반환 + Redis PROCESSING:{hash} 설정 확인")
    void should_return_NEW_and_set_PROCESSING_when_key_does_not_exist() {
        // Given: 해당 키가 Redis에 존재하지 않음 (setUp + AfterEach 보장)

        // When: execute 최초 호출
        IdempotencyCheckResult result = luaScript.execute(testKey, HASH_A);

        // Then: NEW 반환
        assertThat(result.type())
                .as("최초 호출 → NEW")
                .isEqualTo(ResultType.NEW);
        assertThat(result.cachedResponse())
                .as("NEW → cachedResponse null")
                .isNull();

        // Redis 직접 조회 — PROCESSING:{hash} 형식 확인
        String stored = redisTemplate.opsForValue().get(KEY_PREFIX + testKey);
        assertThat(stored)
                .as("Redis에 PROCESSING:{hash} 값 저장")
                .isEqualTo("PROCESSING:" + HASH_A);
    }

    // =========================================================================
    // Scenario 2: PROCESSING 상태 + 같은 hash → PROCESSING 반환
    // =========================================================================

    // Scenario: [happy] PROCESSING 상태 키 + 같은 hash → PROCESSING 반환
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("PROCESSING 상태 키 + 같은 hash → PROCESSING 반환")
    void should_return_PROCESSING_when_key_already_exists_in_PROCESSING_with_same_hash() {
        // Given: Redis에 PROCESSING:{hash} 선설정
        redisTemplate.opsForValue().set(KEY_PREFIX + testKey, "PROCESSING:" + HASH_A,
                900, TimeUnit.SECONDS);

        // When: 동일 hash로 execute 호출
        IdempotencyCheckResult result = luaScript.execute(testKey, HASH_A);

        // Then: PROCESSING 반환
        assertThat(result.type())
                .as("PROCESSING 상태 + 같은 hash → PROCESSING")
                .isEqualTo(ResultType.PROCESSING);
        assertThat(result.cachedResponse())
                .as("PROCESSING → cachedResponse null")
                .isNull();
    }

    // =========================================================================
    // Scenario 3: COMPLETED 상태 + 같은 hash → COMPLETED + cachedResponse
    // =========================================================================

    // Scenario: [happy] COMPLETED 상태 키 + 같은 hash → COMPLETED + cachedResponse 반환
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("COMPLETED 상태 키 + 같은 hash → COMPLETED + cachedResponse 반환")
    void should_return_COMPLETED_with_cached_response_when_key_in_COMPLETED_with_same_hash() {
        // Given: Redis에 COMPLETED:{hash}:{responseJson} 선설정
        String responseJson = "{\"bookingId\":999,\"status\":\"COMPLETED\"}";
        redisTemplate.opsForValue().set(
                KEY_PREFIX + testKey,
                "COMPLETED:" + HASH_A + ":" + responseJson,
                900, TimeUnit.SECONDS);

        // When: 동일 hash로 execute 호출
        IdempotencyCheckResult result = luaScript.execute(testKey, HASH_A);

        // Then: COMPLETED + cachedResponse 정합
        assertThat(result.type())
                .as("COMPLETED 상태 + 같은 hash → COMPLETED")
                .isEqualTo(ResultType.COMPLETED);
        assertThat(result.cachedResponse())
                .as("cachedResponse가 저장된 responseJson과 일치")
                .isEqualTo(responseJson);
    }

    // =========================================================================
    // Scenario 4: 저장된 hash 와 다른 hash → HASH_MISMATCH
    // =========================================================================

    // Scenario: [edge:tampering] 다른 hash 호출 → HASH_MISMATCH 반환
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("저장된 hash와 다른 hash 호출 → HASH_MISMATCH 반환")
    void should_return_HASH_MISMATCH_when_stored_hash_differs() {
        // Given: Redis에 PROCESSING:{HASH_A} 선설정
        redisTemplate.opsForValue().set(KEY_PREFIX + testKey, "PROCESSING:" + HASH_A,
                900, TimeUnit.SECONDS);

        // When: HASH_B (다른 hash)로 execute 호출
        IdempotencyCheckResult result = luaScript.execute(testKey, HASH_B);

        // Then: HASH_MISMATCH
        assertThat(result.type())
                .as("다른 hash → HASH_MISMATCH")
                .isEqualTo(ResultType.HASH_MISMATCH);
    }

    // =========================================================================
    // Scenario 5: 신규 키 생성 후 TTL 900초 설정 확인
    // =========================================================================

    // Scenario: [happy] 신규 키 execute → TTL 900초 설정 (870~900 범위 허용)
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("happy")
    @DisplayName("신규 키 execute → TTL 900초 설정 (870~900 초 범위)")
    void should_set_TTL_900_seconds_when_creating_new_key() {
        // Given: 해당 키가 Redis에 존재하지 않음

        // When: execute 최초 호출
        luaScript.execute(testKey, HASH_A);

        // Then: Redis TTL 이 900초 이하, 870초 이상 (테스트 실행 지연 감안 30초 허용)
        Long ttl = redisTemplate.getExpire(KEY_PREFIX + testKey, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("TTL이 설정돼 있어야 함 (0 또는 -1은 TTL 미설정)")
                .isGreaterThan(0L);
        assertThat(ttl)
                .as("TTL 상한 900초")
                .isLessThanOrEqualTo(900L);
        assertThat(ttl)
                .as("TTL 하한 870초 (30초 여유)")
                .isGreaterThanOrEqualTo(870L);
    }

    // =========================================================================
    // Scenario 6: 100 동시 동일 키 SETNX → NEW 정확히 1, PROCESSING 99 (Lua atomic)
    // =========================================================================

    // Scenario: [edge:concurrency] 100 동시 동일 키 execute → NEW 1건, PROCESSING 99건 (Lua atomic 보장)
    // Source: docs/features/feature-001-idempotency-handling.md
    @Test
    @Tag("edge")
    @Tag("edge:concurrency")
    @DisplayName("100 동시 동일 키 execute → NEW 정확히 1건, PROCESSING 99건 (Lua atomic 보장)")
    void should_remain_atomic_under_concurrent_same_key_writes() throws Exception {
        // Given: 100 스레드가 동일 key + 동일 hash 동시 execute 준비
        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);   // 동시 시작 보장
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger newCount = new AtomicInteger(0);
        AtomicInteger processingCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        // When: 동시 execute 발사
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();  // 모든 스레드 대기 후 동시 진입
                    IdempotencyCheckResult result = luaScript.execute(testKey, HASH_A);
                    switch (result.type()) {
                        case NEW -> newCount.incrementAndGet();
                        case PROCESSING -> processingCount.incrementAndGet();
                        default -> otherCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Then: NEW 정확히 1건 (Lua SETNX atomic 보장), PROCESSING 99건, 기타 0건
        assertThat(newCount.get())
                .as("Lua atomic SETNX → 정확히 1건만 NEW")
                .isEqualTo(1);
        assertThat(processingCount.get())
                .as("나머지 99건은 PROCESSING (이미 선점)")
                .isEqualTo(threadCount - 1);
        assertThat(otherCount.get())
                .as("HASH_MISMATCH / 예외 0건 (모두 같은 hash)")
                .isEqualTo(0);
    }
}

package com.booking.infrastructure.redis;

import com.booking.application.IdempotencyCheckResult;
import com.booking.application.IdempotencyCheckResult.ResultType;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Idempotency check + reserve / mark-completed via Redis Lua scripts
 * (ADR-002 atomic, ADR-006).
 *
 * <p>Resilience4j {@code @CircuitBreaker} + {@code @Bulkhead}({@code redisOps})
 * 으로 wrapping — Sentinel failover / slow death 시 빠르게 차단해 Tomcat 스레드
 * 점유를 막고, fallback 동작은 메소드별로 다르다:
 * <ul>
 *   <li>{@link #execute(UUID, String)} — Redis 1차 진입. fallback 시
 *       {@link RedisUnavailableException} throw → 호출자가 DB 2차 방어선으로
 *       전환 (ADR-007).</li>
 *   <li>{@link #markCompleted(UUID, String, String)} — DB 트랜잭션 commit 후 호출.
 *       Redis 갱신 실패는 DB 가 source of truth 라 정합성 문제 없음 → fallback 에서
 *       warn log 만 남기고 정상 반환.</li>
 * </ul>
 */
@Component
public class IdempotencyLuaScript {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLuaScript.class);

    private static final String KEY_PREFIX = "idempotency:";
    private static final long DEFAULT_TTL_SECONDS = 900;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> setnxScript;
    private final DefaultRedisScript<List> completeScript;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public IdempotencyLuaScript(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.setnxScript = new DefaultRedisScript<>();
        this.setnxScript.setScriptSource(
            new ResourceScriptSource(new ClassPathResource("lua/idempotency_setnx.lua")));
        this.setnxScript.setResultType(List.class);

        this.completeScript = new DefaultRedisScript<>();
        this.completeScript.setScriptSource(
            new ResourceScriptSource(new ClassPathResource("lua/idempotency_complete.lua")));
        this.completeScript.setResultType(List.class);
    }

    @CircuitBreaker(name = "redisOps", fallbackMethod = "executeFallback")
    @Bulkhead(name = "redisOps")
    public IdempotencyCheckResult execute(UUID key, String bodyHash) {
        List<String> keys = List.of(KEY_PREFIX + key);
        @SuppressWarnings("unchecked")
        List<Object> raw = redisTemplate.execute(setnxScript, keys,
            bodyHash, String.valueOf(DEFAULT_TTL_SECONDS));
        return parseResult(raw);
    }

    /**
     * PROCESSING → COMPLETED 전이 (DB 트랜잭션 commit 후 호출).
     *
     * <p>Redis 갱신 실패는 DB가 source of truth 이므로 정합성 문제 없음 — 다음 요청
     * 시 Redis miss → DB 2차 fallback 에서 cached 응답 정상 반환. 따라서 fallback
     * 은 warn log + 정상 종료.
     */
    @CircuitBreaker(name = "redisOps", fallbackMethod = "markCompletedFallback")
    @Bulkhead(name = "redisOps")
    public void markCompleted(UUID key, String bodyHash, String responsePayload) {
        List<String> keys = List.of(KEY_PREFIX + key);
        redisTemplate.execute(completeScript, keys,
            bodyHash, responsePayload, String.valueOf(DEFAULT_TTL_SECONDS));
    }

    /**
     * idempotency key 강제 해제 — NEW 단계 진입 후 비즈니스 실패 (예: SOLD_OUT) 로
     * DB INSERT 전 cleanup 시 호출. 클라이언트가 새 키로 재시도 가능하게 한다.
     *
     * <p>DB 영속 없는 단계라 Redis DEL 만으로 clean. fallback 은 warn log only — Redis
     * 실패해도 15분 TTL 만료 시 자동 cleanup, 그 사이 동일 키 재시도는 PROCESSING 으로
     * 응답 (409). 클라이언트 영향 작음.
     */
    @CircuitBreaker(name = "redisOps", fallbackMethod = "releaseKeyFallback")
    @Bulkhead(name = "redisOps")
    public void releaseKey(UUID key) {
        redisTemplate.delete(KEY_PREFIX + key);
    }

    private IdempotencyCheckResult parseResult(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Lua returned empty result");
        }
        String type = (String) raw.get(0);
        return switch (type) {
            case "NEW" -> new IdempotencyCheckResult(ResultType.NEW, null);
            case "PROCESSING" -> new IdempotencyCheckResult(ResultType.PROCESSING, null);
            case "COMPLETED" -> {
                String cached = raw.size() > 2 ? (String) raw.get(2) : null;
                if (cached != null && cached.isEmpty()) {
                    cached = null;
                }
                yield new IdempotencyCheckResult(ResultType.COMPLETED, cached);
            }
            case "HASH_MISMATCH" -> new IdempotencyCheckResult(ResultType.HASH_MISMATCH, null);
            default -> throw new IllegalStateException("Unknown Lua result type: " + type);
        };
    }

    @SuppressWarnings("unused")
    private IdempotencyCheckResult executeFallback(UUID key, String bodyHash, Throwable t) {
        throw new RedisUnavailableException(
            "Redis unavailable for idempotency check (key=" + key + ")", t);
    }

    @SuppressWarnings("unused")
    private void markCompletedFallback(UUID key, String bodyHash, String responsePayload, Throwable t) {
        log.warn("[REDIS_MARK_COMPLETED_FALLBACK] key={} (DB is source of truth, next request will fallback to DB)",
            key, t);
    }

    @SuppressWarnings("unused")
    private void releaseKeyFallback(UUID key, Throwable t) {
        log.warn("[REDIS_RELEASE_KEY_FALLBACK] key={} (15분 TTL 만료까지 같은 키 재시도는 PROCESSING 응답)",
            key, t);
    }
}

package com.booking.infrastructure.redis;

import com.booking.application.IdempotencyCheckResult;
import com.booking.application.IdempotencyCheckResult.ResultType;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Idempotency check + reserve via Redis Lua script (ADR-002 atomic, ADR-006).
 *
 * <p>Resilience4j {@code @CircuitBreaker} + {@code @Bulkhead}({@code redisOps})
 * 으로 wrapping — Sentinel failover / slow death 시 빠르게 차단해
 * Tomcat 스레드 점유를 막고, fallback 으로 {@link RedisUnavailableException} 을
 * throw 해 호출자가 DB 2차 방어선으로 전환할 수 있게 한다 (ADR-007).
 *
 * <p>Lua script 위치: {@code classpath:lua/idempotency_setnx.lua}.
 */
@Component
public class IdempotencyLuaScript {

    private static final String KEY_PREFIX = "idempotency:";
    private static final long DEFAULT_TTL_SECONDS = 900;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> setnxScript;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public IdempotencyLuaScript(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.setnxScript = new DefaultRedisScript<>();
        this.setnxScript.setScriptSource(
            new ResourceScriptSource(new ClassPathResource("lua/idempotency_setnx.lua")));
        this.setnxScript.setResultType(List.class);
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

    /**
     * Resilience4j fallback — Circuit Breaker OPEN 또는 Bulkhead full 시 호출.
     *
     * <p>{@link RedisUnavailableException} 으로 변환해 호출자가 DB 2차 방어선
     * 또는 503 으로 분기할 수 있게 한다.
     */
    @SuppressWarnings("unused") // referenced by @CircuitBreaker fallbackMethod
    private IdempotencyCheckResult executeFallback(UUID key, String bodyHash, Throwable t) {
        throw new RedisUnavailableException(
            "Redis unavailable for idempotency check (key=" + key + ")", t);
    }
}

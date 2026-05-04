package com.booking.infrastructure.redis;

import com.booking.domain.stock.StockRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stock 카운터 Redis 어댑터 (ADR-008 / ADR-002 / ADR-007).
 *
 * <p>{@code IdempotencyLuaScript} 와 동일한 Resilience4j 패턴 — {@code redisOps} cache 재사용
 * (Bulkhead / CircuitBreaker 인스턴스 공유로 Redis 보호 일관). {@code tryHold} fallback 은
 * {@link RedisUnavailableException} throw → ADR-007 Fail-Closed → 503.
 */
@Component
public class StockRedisAdapter implements StockRepository {

    private static final String STOCK_KEY_PREFIX = "stock:accommodation:";
    private static final String HOLD_KEY_PREFIX_USER = "hold:user:";
    private static final String HOLD_KEY_INFIX_PRODUCT = ":product:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> holdScript;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public StockRedisAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.holdScript = new DefaultRedisScript<>();
        this.holdScript.setScriptSource(
            new ResourceScriptSource(new ClassPathResource("lua/stock_hold.lua")));
        this.holdScript.setResultType(List.class);
    }

    @CircuitBreaker(name = "redisOps", fallbackMethod = "tryHoldFallback")
    @Bulkhead(name = "redisOps")
    @Override
    public boolean tryHold(long accommodationId, long userId, int ttlSeconds) {
        List<String> keys = List.of(stockKey(accommodationId));
        @SuppressWarnings("unchecked")
        List<Object> raw = redisTemplate.execute(holdScript, keys,
            holdKey(userId, accommodationId), String.valueOf(ttlSeconds));
        return parseHoldResult(raw);
    }

    @Override
    public void init(long accommodationId, int initialCount) {
        redisTemplate.opsForValue().set(stockKey(accommodationId), String.valueOf(initialCount));
    }

    private boolean parseHoldResult(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Lua returned empty result for stock_hold");
        }
        Object first = raw.get(0);
        if (first instanceof Number n) {
            return n.longValue() == 1L;
        }
        throw new IllegalStateException("Unexpected Lua result type: " + first);
    }

    private static String stockKey(long accommodationId) {
        return STOCK_KEY_PREFIX + accommodationId;
    }

    private static String holdKey(long userId, long accommodationId) {
        return HOLD_KEY_PREFIX_USER + userId + HOLD_KEY_INFIX_PRODUCT + accommodationId;
    }

    @SuppressWarnings("unused")
    private boolean tryHoldFallback(long accommodationId, long userId, int ttlSeconds, Throwable t) {
        throw new RedisUnavailableException(
            "Redis unavailable for stock tryHold (acc=" + accommodationId + ", user=" + userId + ")", t);
    }
}

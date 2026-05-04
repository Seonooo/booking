package com.booking.infrastructure.redis;

/**
 * Redis 호출이 Resilience4j Circuit Breaker / Bulkhead 에 의해 차단되거나
 * Sentinel failover 윈도우 안에서 응답을 받지 못한 경우 throw.
 *
 * <p>호출자(application service)는 ADR-006 §흐름에 따라 DB 2차 방어선으로 fallback
 * 하거나, fallback 이 불가능한 경로(예: Redis 만 의존하는 stock counter)에서는
 * ADR-007 Fail-Closed 정책에 따라 503 으로 변환한다.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

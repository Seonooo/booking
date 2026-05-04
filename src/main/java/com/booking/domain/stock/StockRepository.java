package com.booking.domain.stock;

/**
 * 재고 카운터 driven port (ADR-008 / ADR-014).
 *
 * <p>Redis-only 카운터 — RDB 테이블 X (ERD §2.2 *"Stock counter — Redis 데이터(테이블 아님)"*).
 *
 * <p>메소드:
 * <ul>
 *   <li>{@link #tryHold} — atomic DECR + hold key SET (Lua atomic, ADR-002).</li>
 *   <li>{@link #release} — atomic INCR + DEL hold key (idempotent, sweeper / Saga 보상 / Reconciliation 공통).</li>
 *   <li>{@link #init} — test fixture / admin API 진입점.</li>
 * </ul>
 */
public interface StockRepository {

    /**
     * atomic 진입 시도 — Lua DECR + hold key SET (TTL).
     *
     * @return {@code true} 진입 성공 (재고 DECR 완료, hold key TTL set), {@code false} 재고 소진
     *         또는 같은 사용자 hold key 이미 존재
     */
    boolean tryHold(long accommodationId, long userId, int ttlSeconds);

    /**
     * atomic 재고 회수 — Lua INCR + DEL hold key. Idempotent — hold key 없으면 INCR 미수행
     * (over-INCR 차단). 호출자 (sweeper / Saga 보상 / Reconciliation) 가 booking 의 CAS 정합 보장
     * 후 호출.
     */
    void release(long accommodationId, long userId);

    /**
     * 재고 초기화 — test fixture / admin API 용.
     */
    void init(long accommodationId, int initialCount);
}

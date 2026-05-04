package com.booking.application;

/**
 * 재고 소진 — {@link com.booking.domain.stock.StockRepository#tryHold} 가 false 반환 시
 * application 레이어가 throw. {@code GlobalExceptionHandler} 가 409 Conflict 매핑.
 *
 * <p>ADR-008 §재고 풀림 시 분배 정책 — *"새로고침 폴링으로 재참여"*. 409 의 *resource state
 * 충돌* 시맨틱 정합 (`docs/CONVENTIONS-CODE.md` §3).
 */
public class StockSoldOutException extends RuntimeException {

    public StockSoldOutException() {
        super("SOLD_OUT");
    }
}

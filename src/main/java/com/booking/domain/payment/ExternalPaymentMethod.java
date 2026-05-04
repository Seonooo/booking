package com.booking.domain.payment;

/**
 * 외부 PG/페이 API 호출 책임을 갖는 결제 수단 (ADR-009 §클래스 계층).
 *
 * <p>실패 시 보상 (취소 API) 책임이 본 계층에 있다 — Saga 보상 트랜잭션
 * (ADR-009 §처리 흐름). DB 변경 전에 호출되며 {@code @Transactional} 밖에서
 * 실행되어야 한다 (CLAUDE.md CRITICAL #1).
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code CardPayment} (본 PR Phase 3.4 활성)</li>
 *   <li>{@code YPayPayment} (future feature)</li>
 *   <li>{@code KakaoPayPayment} (future feature, ADR-009 §4-4 OCP 측정)</li>
 * </ul>
 */
public interface ExternalPaymentMethod extends PaymentMethod {

    /**
     * PG API 호출. 동기 응답.
     *
     * @return 외부 결제 ID + 상태
     * @throws RuntimeException PG 거절 / timeout / 네트워크 장애 시
     */
    PaymentResult execute(PaymentRequest request);

    /**
     * Saga 보상 — DB 트랜잭션 실패 또는 reconciliation 시 PG 취소 API 호출.
     *
     * <p>본 PR 에서는 미호출 (CardPayment.cancel skeleton). future feature
     * (Saga / Reconciliation) 에서 활성.
     *
     * @param paymentKey   PG 가 부여한 외부 결제 ID
     * @param cancelAmount 취소 금액 (부분 취소 지원)
     */
    void cancel(String paymentKey, long cancelAmount);

    /**
     * PG 상태 조회 — Reconciliation worker (ADR-011) 가 booking UNKNOWN row 의 결과 확정용.
     *
     * <p>1차 기준 = {@code externalPaymentId}. fallback = {@code attemptId} (PG idempotency 헤더).
     *
     * @return SUCCESS / FAILED / NOT_FOUND
     */
    PaymentStatusResult queryStatus(String externalPaymentId, java.util.UUID attemptId);
}

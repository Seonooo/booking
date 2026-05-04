package com.booking.domain.payment;

/**
 * {@link PaymentComposition} 생성자 불변식 위반 시 throw (ADR-009 §Domain 검증).
 *
 * <p>대표 사례: 외부 결제 수단(카드/Y페이) 2개 이상 혼용. GlobalExceptionHandler 가
 * HTTP 400 으로 변환한다 (CONVENTIONS-CODE.md §3 — 400 = 도메인 invariant 위반).
 */
public class InvalidPaymentCompositionException extends RuntimeException {

    public InvalidPaymentCompositionException(String message) {
        super(message);
    }
}

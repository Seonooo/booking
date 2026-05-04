package com.booking.domain.payment;

/**
 * 결제 수단의 공통 표지 (ADR-009 §클래스 계층).
 *
 * <p>본 인터페이스는 분류 표지만 담당하며 실제 실행 시그니처는 두 하위 계층
 * ({@link ExternalPaymentMethod} / {@link InternalPaymentMethod}) 에서 정의한다.
 * 외부 PG 호출과 내부 DB 차감의 본질 차이를 타입 시스템에 노출하기 위함이며,
 * {@link PaymentComposition} 의 혼용 정책 검증 ({@code instanceof
 * ExternalPaymentMethod}) 에서 활용된다.
 */
public interface PaymentMethod {

    String getMethodType();
}

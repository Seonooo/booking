package com.booking.domain.payment;

/**
 * 내부 DB 차감 책임을 갖는 결제 수단 (ADR-009 §클래스 계층).
 *
 * <p>외부 호출과 달리 DB 트랜잭션 안에서 실행되며 보상은 트랜잭션 롤백으로
 * 단순 처리된다 (별도 cancel API 없음). 본 인터페이스는 시그니처만 정의하며,
 * 본 PR Phase 3.4 에서는 구현체를 도입하지 않는다 — future feature
 * ({@code PointPayment}) 진입 시 단일 파일 추가로 활성화된다.
 */
public interface InternalPaymentMethod extends PaymentMethod {

    /**
     * 내부 DB 차감 (예: point.balance -= amount).
     *
     * <p>호출자는 본 메소드를 {@code @Transactional} 컨텍스트 안에서 호출해야
     * 한다 (보상 = 트랜잭션 롤백).
     */
    void execute(long userId, long amount);
}

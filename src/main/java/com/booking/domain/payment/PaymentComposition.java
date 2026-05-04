package com.booking.domain.payment;

import java.util.List;
import java.util.Objects;

/**
 * 결제 수단 조합 Value Object (ADR-009 §Domain 검증, §4-3 검증 위치).
 *
 * <p>혼용 정책 invariant 를 생성자에서 강제 — 외부 결제 수단(카드/Y페이) 동시
 * 사용은 불가하다. 검증을 *"카드 + Y페이"* 같은 이름 비교가 아니라 *"외부 결제
 * 수단 1개 초과 불가"* 라는 추상화 수준에서 작성하므로, 카카오페이 등 신규
 * 외부 결제 추가 시에도 동일 규칙이 자동 적용된다 (ADR-009 §4-4 OCP).
 *
 * <p>본 PR Phase 3.4 에서는 외부 결제 단일 (CardPayment) 만 사용하므로 invariant
 * 가 시나리오상 트리거되지 않지만, future feature 가 외부 결제를 추가할 때
 * 자동으로 차단되도록 검증을 본격 활성화한다.
 */
public class PaymentComposition {

    private final List<PaymentMethod> methods;

    public PaymentComposition(List<PaymentMethod> methods) {
        Objects.requireNonNull(methods, "methods");
        if (methods.isEmpty()) {
            throw new InvalidPaymentCompositionException("결제 수단이 비어있습니다");
        }
        long externalCount = methods.stream()
            .filter(m -> m instanceof ExternalPaymentMethod)
            .count();
        if (externalCount > 1) {
            throw new InvalidPaymentCompositionException(
                "외부 결제 수단(카드/Y페이) 2개 이상 혼용 불가");
        }
        this.methods = List.copyOf(methods);
    }

    /**
     * 외부 결제 단일 호출 (ADR-009 §처리 흐름 단계 5). 본 PR 에서는 ExternalPaymentMethod
     * 가 정확히 1개 존재하면 그 메소드의 {@code execute} 를 호출, 없으면 internal-only
     * 결제로 간주해 호출 자체를 skip 하고 null 을 반환할 수도 있지만 본 PR scope
     * 에서는 외부 1개를 가정한다 (향후 internal-only 케이스는 PointPayment 도입 시 분기).
     */
    public PaymentResult executeExternal(PaymentRequest request) {
        ExternalPaymentMethod external = methods.stream()
            .filter(m -> m instanceof ExternalPaymentMethod)
            .map(ExternalPaymentMethod.class::cast)
            .findFirst()
            .orElseThrow(() -> new InvalidPaymentCompositionException(
                "외부 결제 수단이 없습니다"));
        return external.execute(request);
    }

    /**
     * 내부 결제 (포인트) 호출 — methods 안의 모든 InternalPaymentMethod 의 execute 호출.
     *
     * <p>본 PR scope — 모든 internal method 가 같은 amount 사용 (단일 포인트 차감 가정).
     * 다중 internal method (예: 포인트 + 쿠폰) 분배는 future — `Map<PaymentMethod, BigDecimal>`
     * 시그니처 도입 검토.
     *
     * <p>호출자 (BookingService) 는 본 메소드를 {@code @Transactional} 안에서 호출 — 보상 = 트랜잭션 롤백.
     */
    public void executeInternal(long userId, long amount) {
        methods.stream()
            .filter(m -> m instanceof InternalPaymentMethod)
            .map(InternalPaymentMethod.class::cast)
            .forEach(m -> m.execute(userId, amount));
    }

    public List<PaymentMethod> getMethods() {
        return methods;
    }
}

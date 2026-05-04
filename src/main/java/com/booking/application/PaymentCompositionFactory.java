package com.booking.application;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.InvalidPaymentCompositionException;
import com.booking.domain.payment.PaymentComposition;
import com.booking.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link CreateBookingRequest} → {@link PaymentComposition} 변환 책임 (SRP 분리).
 *
 * <p>이전에는 {@link BookingService} 가 *결제 수단 lookup + Composition build* 책임을
 * 같이 가졌으나, SOLID §SRP / §DIP 정합으로 본 클래스로 분리. BookingService 는
 * *예약 오케스트레이션* 만 담당.
 *
 * <p>OCP — 새 결제 수단 (예: 카카오페이) 추가 시:
 * <ol>
 *   <li>새 {@link PaymentMethod} 구현 1개 + Spring Bean 등록</li>
 *   <li>본 클래스 / BookingService 변경 0 (Map 자동 lookup)</li>
 * </ol>
 *
 * <p>변경 영역 (정책 추가 시):
 * <ul>
 *   <li>VIP 할인 / 이벤트 결제 수단 / 동적 분기 → 본 클래스만 변경</li>
 *   <li>BookingService = 결제 정책 추가에 닫힘</li>
 * </ul>
 */
@Component
public class PaymentCompositionFactory {

    private static final String POINT_METHOD_TYPE = "POINT";

    private final Map<String, PaymentMethod> methodsByType;

    public PaymentCompositionFactory(List<PaymentMethod> paymentMethods) {
        this.methodsByType = paymentMethods.stream()
            .collect(Collectors.toMap(PaymentMethod::getMethodType, Function.identity()));
    }

    /**
     * paymentMethod (외부 결제) + points (선택, 내부 결제) → PaymentComposition.
     *
     * @throws InvalidPaymentCompositionException 지원하지 않는 결제 수단 / 외부 결제가 아님 / POINT 미설정
     */
    public PaymentComposition create(CreateBookingRequest request) {
        String pmType = request.paymentMethod().toUpperCase();
        PaymentMethod external = methodsByType.get(pmType);
        if (!(external instanceof ExternalPaymentMethod)) {
            throw new InvalidPaymentCompositionException("지원하지 않는 결제 수단: " + pmType);
        }
        List<PaymentMethod> methods = new ArrayList<>();
        methods.add(external);
        if (request.points() > 0) {
            PaymentMethod point = methodsByType.get(POINT_METHOD_TYPE);
            if (point == null) {
                throw new InvalidPaymentCompositionException("포인트 결제 미설정 — 시스템 오류");
            }
            methods.add(point);
        }
        return new PaymentComposition(methods);
    }
}

package com.booking.application;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.InternalPaymentMethod;
import com.booking.domain.payment.InvalidPaymentCompositionException;
import com.booking.domain.payment.PaymentComposition;
import com.booking.domain.payment.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test — PaymentCompositionFactory (Mockito mock).
 *
 * <p>SRP 분리 후 Factory 가 *paymentMethod String + points → PaymentComposition* 책임.
 * 본 unit test 가 Factory 의 모든 분기를 빠르게 검증 (Integration test 무관 — Mockito mock).
 */
class PaymentCompositionFactoryTest {

    private ExternalPaymentMethod cardPayment;
    private ExternalPaymentMethod ypayPayment;
    private InternalPaymentMethod pointPayment;
    private PaymentCompositionFactory factory;

    @BeforeEach
    void setUp() {
        cardPayment = mock(ExternalPaymentMethod.class);
        when(cardPayment.getMethodType()).thenReturn("CARD");
        ypayPayment = mock(ExternalPaymentMethod.class);
        when(ypayPayment.getMethodType()).thenReturn("YPAY");
        pointPayment = mock(InternalPaymentMethod.class);
        when(pointPayment.getMethodType()).thenReturn("POINT");

        factory = new PaymentCompositionFactory(List.of(cardPayment, ypayPayment, pointPayment));
    }

    private CreateBookingRequest req(String pm, long points) {
        return new CreateBookingRequest(1001L, 42L, new BigDecimal("50000.00"), pm, points);
    }

    @Test
    @Tag("happy")
    @DisplayName("CARD only (points=0) → composition methods = [cardPayment]")
    void should_create_card_only_composition() {
        PaymentComposition composition = factory.create(req("CARD", 0L));

        assertThat(composition.getMethods())
            .as("CARD only")
            .containsExactly(cardPayment);
    }

    @Test
    @Tag("happy")
    @DisplayName("YPAY + POINT (points>0) → composition methods = [ypayPayment, pointPayment]")
    void should_create_ypay_plus_point_composition() {
        PaymentComposition composition = factory.create(req("YPAY", 5000L));

        assertThat(composition.getMethods())
            .as("YPAY + POINT")
            .containsExactly(ypayPayment, pointPayment);
    }

    @Test
    @Tag("happy")
    @DisplayName("paymentMethod 대소문자 무관 — 'card' → CARD")
    void should_normalize_payment_method_case() {
        PaymentComposition composition = factory.create(req("card", 0L));

        assertThat(composition.getMethods()).containsExactly(cardPayment);
    }

    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("지원하지 않는 결제 수단 → InvalidPaymentCompositionException")
    void should_throw_when_payment_method_unsupported() {
        assertThatThrownBy(() -> factory.create(req("UNKNOWN_METHOD", 0L)))
            .isInstanceOf(InvalidPaymentCompositionException.class)
            .hasMessageContaining("지원하지 않는 결제 수단");
    }

    @Test
    @Tag("edge")
    @Tag("edge:tampering")
    @DisplayName("InternalPaymentMethod (POINT) 만 paymentMethod 로 지정 시 → 외부 결제 아님 → throw")
    void should_throw_when_payment_method_is_internal_only() {
        assertThatThrownBy(() -> factory.create(req("POINT", 0L)))
            .isInstanceOf(InvalidPaymentCompositionException.class)
            .hasMessageContaining("지원하지 않는 결제 수단");
    }

    @Test
    @Tag("edge")
    @Tag("edge:failure")
    @DisplayName("POINT Bean 미설정 + points>0 → InvalidPaymentCompositionException (시스템 오류)")
    void should_throw_when_point_bean_missing_and_points_positive() {
        // POINT Bean 없는 setup
        PaymentCompositionFactory factoryWithoutPoint = new PaymentCompositionFactory(
            List.of(cardPayment, ypayPayment));

        assertThatThrownBy(() -> factoryWithoutPoint.create(req("CARD", 5000L)))
            .isInstanceOf(InvalidPaymentCompositionException.class)
            .hasMessageContaining("포인트 결제 미설정");
    }
}

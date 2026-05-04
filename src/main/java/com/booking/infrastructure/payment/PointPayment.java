package com.booking.infrastructure.payment;

import com.booking.domain.payment.InternalPaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 포인트 결제 driven adapter (REQUIREMENTS §1.2 / ADR-009 §클래스 계층).
 *
 * <p>본 PR scope — **Mock 구현**. 실제 point_ledger 차감은 ERD §2.2 *out-of-scope* (포인트 도메인).
 * future feature 진입 시 본 클래스의 execute 안에서 point_ledger UPDATE 본격.
 *
 * <p>본 PR 은 *결제 확장성 검증* 만 — InternalPaymentMethod 인터페이스 활성 + BookingService 의
 * Map lookup OCP 가설 검증.
 */
@Component
public class PointPayment implements InternalPaymentMethod {

    private static final Logger log = LoggerFactory.getLogger(PointPayment.class);
    private static final String METHOD_TYPE = "POINT";

    @Override
    public String getMethodType() {
        return METHOD_TYPE;
    }

    @Override
    public void execute(long userId, long amount) {
        // Mock — log only. point_ledger 도메인 도입 시 본격 차감.
        log.info("[POINT_PAYMENT_MOCK] userId={} amount={} (point_ledger 차감 — future feature)",
            userId, amount);
    }
}

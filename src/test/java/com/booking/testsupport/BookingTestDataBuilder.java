package com.booking.testsupport;

import com.booking.api.booking.dto.CreateBookingRequest;

import java.math.BigDecimal;

/**
 * Test-only builder — src/test/ 에만 위치. production 코드 아님.
 * Booking / CreateBookingRequest 생성 helper (test-author.md §Test Data Builders).
 * 모든 default 값은 valid (즉시 도메인 invariant 만족).
 */
public class BookingTestDataBuilder {

    private long userId = 1001L;
    private long productId = 42L;
    private BigDecimal amount = new BigDecimal("50000.00");
    private String paymentMethod = "CARD";
    private long points = 0L;

    public BookingTestDataBuilder withUserId(long userId) {
        this.userId = userId;
        return this;
    }

    public BookingTestDataBuilder withProductId(long productId) {
        this.productId = productId;
        return this;
    }

    public BookingTestDataBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public BookingTestDataBuilder withPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
        return this;
    }

    public BookingTestDataBuilder withPoints(long points) {
        this.points = points;
        return this;
    }

    public CreateBookingRequest buildRequest() {
        return new CreateBookingRequest(userId, productId, amount, paymentMethod, points);
    }

    // --- named factories (흔한 시나리오) ---

    /** 기본 카드 결제 요청 (userId=1001, productId=42, amount=50000, CARD, points=0). */
    public static CreateBookingRequest aDefaultCardRequest() {
        return new BookingTestDataBuilder().buildRequest();
    }

    /** 같은 금액, 다른 결제 수단 (body_hash가 달라지는 tampering 시나리오용). */
    public static CreateBookingRequest aTamperedRequest() {
        return new BookingTestDataBuilder()
                .withPaymentMethod("YPAY")
                .buildRequest();
    }

    /** 포인트 사용 복합 결제 요청. */
    public static CreateBookingRequest aPointComboRequest() {
        return new BookingTestDataBuilder()
                .withAmount(new BigDecimal("45000.00"))
                .withPaymentMethod("CARD")
                .withPoints(5000L)
                .buildRequest();
    }
}

package com.booking.infrastructure.payment;

import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentRequest;
import com.booking.domain.payment.PaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 신용카드 PG 호출 driven adapter (ADR-009 §클래스 계층, ADR-014 재배치 — domain
 * interface는 {@code domain/payment/}, 구현체는 {@code infrastructure/payment/}).
 *
 * <p>본 PR 에서는 단순 RestTemplate 호출. 실제 토스페이먼츠 SDK 통합은 future feature.
 * Saga {@link #cancel(String, long)} 시그니처는 정의하되 본 PR 미호출 (DB 실패 시뮬레이션
 * 시나리오 비포함).
 */
@Component
public class CardPayment implements ExternalPaymentMethod {

    private static final String METHOD_TYPE = "CARD";

    private final RestTemplate restTemplate;
    private final String pgUrl;

    public CardPayment(RestTemplate restTemplate,
                       @Value("${external.pg.url}") String pgUrl) {
        this.restTemplate = restTemplate;
        this.pgUrl = pgUrl;
    }

    @Override
    public String getMethodType() {
        return METHOD_TYPE;
    }

    @Override
    public PaymentResult execute(PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amount().toPlainString());
        body.put("idempotencyKey", request.idempotencyKey());
        body.put("userId", request.userId());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                pgUrl + "/payment", body, Map.class);
            if (response == null) {
                throw new RestClientException("PG returned null body");
            }
            String externalPaymentId = (String) response.get("externalPaymentId");
            String status = (String) response.get("status");
            return new PaymentResult(externalPaymentId, status);
        } catch (RestClientException e) {
            throw new PgCallFailedException("PG call failed", e);
        }
    }

    /**
     * Saga 보상 — DB 트랜잭션 실패 / Reconciliation 시 PG 취소 API 호출.
     * 본 PR Phase 3.4 미호출 (시그니처만 — 후속 feature 에서 활성).
     */
    @Override
    public void cancel(String paymentKey, long cancelAmount) {
        throw new UnsupportedOperationException(
            "CardPayment.cancel — Phase 3.4 미사용. Saga 보상 통합은 future feature.");
    }
}

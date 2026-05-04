package com.booking.infrastructure.payment;

import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentRequest;
import com.booking.domain.payment.PaymentResult;
import com.booking.domain.payment.PaymentStatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Y페이 PG driven adapter (REQUIREMENTS §1.2 / ADR-009 §클래스 계층). CardPayment 패턴 차용.
 *
 * <p>{@code POST /ypay/payment} — Y페이 결제 승인 API. 응답 분기는 CardPayment 와 동일
 * (4XX → PaymentRejected / 5XX/timeout → PaymentTimeout).
 *
 * <p>본 PR Phase 3.1 — execute 만 본격. cancel + queryStatus 는 Saga 보상 / Reconciliation
 * 영역과 통합 시 활성 (future feature). PaymentComposition 의 *외부 결제 1개 초과 불가*
 * invariant 가 카드 ↔ Y페이 혼용 자동 차단 (ADR-009 §Domain 검증).
 */
@Component
public class YPayPayment implements ExternalPaymentMethod {

    private static final Logger log = LoggerFactory.getLogger(YPayPayment.class);
    private static final String METHOD_TYPE = "YPAY";

    private final RestTemplate restTemplate;
    private final String pgUrl;

    public YPayPayment(RestTemplate restTemplate,
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
                pgUrl + "/ypay/payment", body, Map.class);
            if (response == null) {
                throw new PaymentTimeoutException("YPay returned null body", null);
            }
            String externalPaymentId = (String) response.get("externalPaymentId");
            String status = (String) response.get("status");
            return new PaymentResult(externalPaymentId, status);
        } catch (HttpClientErrorException e) {
            HttpStatusCode code = e.getStatusCode();
            throw new PaymentRejectedException(code.value(), e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PaymentTimeoutException("YPay 5XX or timeout: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new PaymentTimeoutException("YPay call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancel(String paymentKey, long cancelAmount) {
        // Saga 보상 / Reconciliation 영역에서 활성 (future feature).
        // 본 PR 의 시나리오 — execute 만 검증.
        log.warn("[YPAY_CANCEL_NOT_IMPLEMENTED] paymentKey={} cancelAmount={} (Saga 보상은 future feature)",
            paymentKey, cancelAmount);
        throw new UnsupportedOperationException("YPayPayment.cancel — future feature");
    }

    @Override
    public PaymentStatusResult queryStatus(String externalPaymentId, UUID attemptId) {
        // Reconciliation 영역에서 활성 (future feature).
        log.warn("[YPAY_QUERY_STATUS_NOT_IMPLEMENTED] externalPaymentId={} attemptId={}",
            externalPaymentId, attemptId);
        return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, externalPaymentId);
    }
}

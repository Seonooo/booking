package com.booking.infrastructure.payment;

import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentRequest;
import com.booking.domain.payment.PaymentResult;
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

/**
 * 신용카드 PG 호출 driven adapter (ADR-009 §클래스 계층, ADR-014 재배치 — domain
 * interface는 {@code domain/payment/}, 구현체는 {@code infrastructure/payment/}).
 *
 * <p>응답 분기 (DECISIONS.md §11 정합):
 * <ul>
 *   <li>2XX 성공 → {@link PaymentResult} 반환</li>
 *   <li>4XX (한도 초과 / 카드 정지) → {@link PaymentRejectedException} (케이스 1)</li>
 *   <li>5XX 또는 응답 timeout → {@link PaymentTimeoutException} (케이스 2)</li>
 * </ul>
 *
 * <p>{@link #cancel} Saga 보상은 feature-005 영역 — 본 PR 미활성.
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
                throw new PaymentTimeoutException("PG returned null body", null);
            }
            String externalPaymentId = (String) response.get("externalPaymentId");
            String status = (String) response.get("status");
            return new PaymentResult(externalPaymentId, status);
        } catch (HttpClientErrorException e) {
            // 4XX — 명확한 거절 (DECISIONS.md §11 케이스 1)
            HttpStatusCode code = e.getStatusCode();
            throw new PaymentRejectedException(code.value(), e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 5XX 또는 timeout — UNKNOWN 영역 (DECISIONS.md §11 케이스 2)
            throw new PaymentTimeoutException("PG 5XX or timeout: " + e.getMessage(), e);
        } catch (RestClientException e) {
            // 기타 RestClient 예외 — UNKNOWN 으로 처리 (안전 default)
            throw new PaymentTimeoutException("PG call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Saga 보상 — DB 트랜잭션 실패 / Reconciliation 시 PG 취소 API 호출.
     * feature-005 영역. 본 PR 미호출.
     */
    @Override
    public void cancel(String paymentKey, long cancelAmount) {
        throw new UnsupportedOperationException(
            "CardPayment.cancel — feature-005 Saga 보상 영역. 본 PR 미호출.");
    }
}

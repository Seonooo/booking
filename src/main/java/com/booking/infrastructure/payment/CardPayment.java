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

    private static final Logger log = LoggerFactory.getLogger(CardPayment.class);
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
     * Saga 보상 — DB 트랜잭션 실패 시 PG 취소 API 호출 (ADR-009 §Saga).
     *
     * <p>{@code POST /payment/cancel} — externalPaymentId 기준 취소. 부분 취소 가능
     * (cancelAmount 파라미터 — 본 PR 은 전액 취소).
     *
     * @throws PgCancelFailedException PG cancel API 호출 실패 — Outbox 재시도가 후속 보장
     */
    @Override
    public void cancel(String paymentKey, long cancelAmount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalPaymentId", paymentKey);
        body.put("cancelAmount", cancelAmount);
        try {
            restTemplate.postForObject(pgUrl + "/payment/cancel", body, Map.class);
            log.info("[PG_CANCEL_OK] externalPaymentId={} cancelAmount={}", paymentKey, cancelAmount);
        } catch (RestClientException e) {
            log.error("[PG_CANCEL_FAILED] externalPaymentId={} message={}", paymentKey, e.getMessage(), e);
            throw new PgCancelFailedException("PG cancel failed: " + e.getMessage(), e);
        }
    }

    /**
     * PG 상태 조회 (ADR-011 §결정 2). Reconciliation worker 가 booking UNKNOWN row 의 결과 확정 시 호출.
     *
     * <p>1차 = {@code GET /payment/{externalPaymentId}}. externalPaymentId 없으면 (PG 응답 미수신
     * 케이스) attemptId fallback = {@code GET /payment/by-attempt/{attemptId}}.
     *
     * <p>응답:
     * <ul>
     *   <li>200 + status = SUCCESS / FAILED → 해당 결과</li>
     *   <li>404 → NOT_FOUND (PG eventual consistency, ADR-011 §핵심 원칙 NOT_FOUND ≠ FAILED)</li>
     *   <li>5XX/timeout → NOT_FOUND fallback (다음 retry cycle 에서 재조회)</li>
     * </ul>
     */
    @Override
    public PaymentStatusResult queryStatus(String externalPaymentId, UUID attemptId) {
        String url = (externalPaymentId != null && !externalPaymentId.isBlank())
            ? pgUrl + "/payment/" + externalPaymentId
            : pgUrl + "/payment/by-attempt/" + attemptId;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) {
                return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, null);
            }
            String status = (String) response.get("status");
            String returnedId = (String) response.get("externalPaymentId");
            if ("SUCCESS".equals(status)) {
                return new PaymentStatusResult(PaymentStatusResult.Status.SUCCESS, returnedId);
            }
            if ("FAILED".equals(status)) {
                return new PaymentStatusResult(PaymentStatusResult.Status.FAILED, returnedId);
            }
            // 기타 status (PENDING, ACKED 등) — UNKNOWN 처리, 다음 cycle 에서 재조회
            return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, returnedId);
        } catch (HttpClientErrorException e) {
            // 404 + 기타 4XX 모두 NOT_FOUND fallback
            if (e.getStatusCode().value() != 404) {
                log.warn("[PG_QUERY_STATUS_4XX] code={} body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            }
            return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, null);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("[PG_QUERY_STATUS_TIMEOUT] {}", e.getMessage());
            return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, null);
        } catch (RestClientException e) {
            log.warn("[PG_QUERY_STATUS_FAILED] {}", e.getMessage());
            return new PaymentStatusResult(PaymentStatusResult.Status.NOT_FOUND, null);
        }
    }
}

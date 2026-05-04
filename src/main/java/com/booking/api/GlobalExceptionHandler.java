package com.booking.api;

import com.booking.application.AccommodationNotFoundException;
import com.booking.application.IdempotencyHashMismatchException;
import com.booking.application.IdempotencyProcessingException;
import com.booking.application.StockSoldOutException;
import com.booking.domain.payment.InvalidPaymentCompositionException;
import com.booking.infrastructure.payment.PaymentRejectedException;
import com.booking.infrastructure.payment.PaymentTimeoutException;
import com.booking.infrastructure.redis.RedisUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * HTTP 응답 매핑 (CONVENTIONS-CODE.md §3 / ADR-006 / ADR-007 / ADR-009).
 *
 * <ul>
 *   <li>409 — 멱등성 키 처리 중 (ADR-006) / 재고 SOLD_OUT (ADR-008)</li>
 *   <li>422 — 멱등성 키 body 변조 (ADR-006)</li>
 *   <li>400 — 도메인 invariant 위반 (ADR-009 PaymentComposition / Bean Validation) / PG 거절 (DECISIONS.md §11 케이스 1)</li>
 *   <li>404 — 존재하지 않는 상품 (REQUIREMENTS §1.1 GET /checkout)</li>
 *   <li>503 — Redis Fail-Closed (ADR-007) / DB UNIQUE 충돌 / PG 5XX/timeout (DECISIONS.md §11 케이스 2)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IdempotencyProcessingException.class)
    public ResponseEntity<Map<String, String>> handleProcessing(IdempotencyProcessingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(StockSoldOutException.class)
    public ResponseEntity<Map<String, String>> handleSoldOut(StockSoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("message", "SOLD_OUT — 재고가 모두 소진되었습니다. 새로고침 후 재시도해 주세요."));
    }

    @ExceptionHandler(IdempotencyHashMismatchException.class)
    public ResponseEntity<Map<String, String>> handleHashMismatch(IdempotencyHashMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(InvalidPaymentCompositionException.class)
    public ResponseEntity<Map<String, String>> handleInvalidComposition(InvalidPaymentCompositionException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(PaymentRejectedException.class)
    public ResponseEntity<Map<String, String>> handlePaymentRejected(PaymentRejectedException e) {
        log.info("[PG_REJECTED] statusCode={} body={}", e.getStatusCode(), e.getResponseBody());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "결제가 거절되었습니다 — 다른 결제 수단으로 재시도해 주세요"));
    }

    @ExceptionHandler(PaymentTimeoutException.class)
    public ResponseEntity<Map<String, String>> handlePaymentTimeout(PaymentTimeoutException e) {
        log.warn("[PG_TIMEOUT] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("message", "처리 중 — 잠시 후 결과 확인 부탁드립니다"));
    }

    @ExceptionHandler(AccommodationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAccommodationNotFound(AccommodationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("message", "존재하지 않는 상품입니다"));
    }

    @ExceptionHandler(RedisUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleRedisUnavailable(RedisUnavailableException e) {
        log.warn("[REDIS_FAIL_CLOSED] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("message", "일시적 장애로 요청을 처리할 수 없습니다. 재시도 부탁드립니다."));
    }

    /**
     * DB UNIQUE constraint 위반 → 503 (ADR-006 §DB 2차 방어선 / ADR-007 Fail-Closed
     * 정신). 동시 동일 멱등성 키가 Redis 1차를 통과한 후 DB 2차 방어선에 차단된 케이스 —
     * 이중 booking 0 보장. 503 으로 응답해 클라이언트 재시도 시 정합 결과 반환.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("[DB_UNIQUE_VIOLATION] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("message", "일시적 충돌로 요청을 처리할 수 없습니다. 재시도 부탁드립니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("요청 데이터 검증 실패");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "필수 헤더 누락: " + e.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "잘못된 형식: " + e.getName()));
    }
}

package com.booking.application;

import com.booking.api.booking.dto.BookingResponse;
import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.application.IdempotencyCheckResult.ResultType;
import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import com.booking.domain.idempotency.IdempotencyStatus;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentComposition;
import com.booking.domain.payment.PaymentRequest;
import com.booking.domain.payment.PaymentResult;
import com.booking.domain.stock.StockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * POST /booking 처리 흐름 (Phase 1 blueprint §4 단계 1~8 / ADR-006 §흐름).
 *
 * <p>흐름:
 * <ol>
 *   <li>body hash 계산</li>
 *   <li>멱등성 체크 (트랜잭션 밖) — Redis 1차 → 결과별 분기</li>
 *   <li>NEW 일 때만: PaymentComposition 검증 → PG 호출 (트랜잭션 밖, CRITICAL #1)</li>
 *   <li>DB 트랜잭션 — booking save + idempotency_key save (DB 2차 방어선)</li>
 *   <li>트랜잭션 후 Redis COMPLETED 갱신 (cached response)</li>
 * </ol>
 *
 * <p>본 PR scope 단순화:
 * <ul>
 *   <li>외부 결제는 CARD 단일 (Y페이/포인트는 future feature — Strategy interface
 *       만 준비)</li>
 *   <li>booking 은 status COMPLETED 직접 생성 (재고 / 상태 전이는 future)</li>
 *   <li>Outbox INSERT 는 본 PR scope 밖 (feature-002+)</li>
 * </ul>
 */
@Service
public class BookingService {

    private static final long IDEMPOTENCY_TTL_MINUTES = 15;
    private static final int STOCK_HOLD_TTL_SECONDS = 300;

    private final IdempotencyKeyService idempotencyKeyService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final BodyHashCalculator bodyHashCalculator;
    private final BookingRepository bookingRepository;
    private final ExternalPaymentMethod cardPayment;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;

    public BookingService(IdempotencyKeyService idempotencyKeyService,
                          IdempotencyKeyRepository idempotencyKeyRepository,
                          BodyHashCalculator bodyHashCalculator,
                          BookingRepository bookingRepository,
                          ExternalPaymentMethod cardPayment,
                          StockRepository stockRepository,
                          ObjectMapper objectMapper) {
        this.idempotencyKeyService = idempotencyKeyService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.bodyHashCalculator = bodyHashCalculator;
        this.bookingRepository = bookingRepository;
        this.cardPayment = cardPayment;
        this.stockRepository = stockRepository;
        this.objectMapper = objectMapper;
    }

    public BookingFlowResult create(UUID idempotencyKey, CreateBookingRequest request) {
        String bodyHash = bodyHashCalculator.calculate(request);

        IdempotencyCheckResult check = idempotencyKeyService.checkAndReserve(idempotencyKey, bodyHash);
        switch (check.type()) {
            case PROCESSING    -> throw new IdempotencyProcessingException();
            case HASH_MISMATCH -> throw new IdempotencyHashMismatchException();
            case COMPLETED     -> {
                // 캐시된 raw JSON 그대로 응답 본문 (Scenario 3 — 캐시 응답)
                return BookingFlowResult.cached(check.cachedResponse());
            }
            case NEW           -> { /* 진행 */ }
        }

        // 단계 3 — 재고 atomic hold (ADR-008 / ADR-002 Lua atomic).
        // 본 PR 은 happy path 만 — PG 실패 시 hold 유지 흐름은 Saga+Outbox feature 영역
        // (ADR-008 amendment 2026-05-04 정합).
        if (!stockRepository.tryHold(request.productId(), request.userId(), STOCK_HOLD_TTL_SECONDS)) {
            // SOLD_OUT — DB INSERT 전이라 idempotency key Redis 만 cleanup.
            // 클라이언트가 새 키로 재시도 가능 (재고 풀린 시점에 진입).
            idempotencyKeyService.releaseKey(idempotencyKey);
            throw new StockSoldOutException();
        }

        // PaymentComposition 검증 (혼용 정책 — 본 PR 은 외부 1개 = CARD 단일)
        PaymentComposition composition = new PaymentComposition(List.of(cardPayment));

        // PG 호출 — @Transactional 밖 (CRITICAL #1)
        PaymentResult pgResult = composition.executeExternal(
            new PaymentRequest(request.amount(), idempotencyKey.toString(), request.userId()));

        // DB 트랜잭션 안에서 booking save + idempotency_key save
        long bookingId = persistBookingAndIdempotency(idempotencyKey, request, bodyHash);

        BookingResponse response = new BookingResponse(bookingId);
        String responseJson = serialize(response);

        // 트랜잭션 commit 후 Redis COMPLETED 갱신
        idempotencyKeyService.complete(idempotencyKey, bodyHash, responseJson, bookingId);

        return BookingFlowResult.fresh(response);
    }

    @Transactional
    protected long persistBookingAndIdempotency(UUID idempotencyKey,
                                                CreateBookingRequest request,
                                                String bodyHash) {
        Instant now = Instant.now();

        // booking save (status COMPLETED 직접 — 재고/상태머신 future feature)
        String compositionSnapshot = "{\"methods\":[{\"type\":\"" + request.paymentMethod().toUpperCase()
            + "\",\"amount\":\"" + request.amount().toPlainString() + "\"}]}";
        Booking booking = new Booking(
            null, idempotencyKey, request.userId(), request.productId(),
            request.amount(), compositionSnapshot,
            BookingStatus.COMPLETED, now, now);
        Booking saved = bookingRepository.save(booking);

        // idempotency_key save (DB 2차 방어선 — UNIQUE constraint)
        IdempotencyKey ikRow = new IdempotencyKey(
            idempotencyKey, request.userId(), bodyHash,
            IdempotencyStatus.PROCESSING, null, null,
            now, now.plus(IDEMPOTENCY_TTL_MINUTES, ChronoUnit.MINUTES));
        idempotencyKeyRepository.save(ikRow);

        return saved.getId();
    }

    private String serialize(BookingResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("BookingResponse JSON 직렬화 실패", e);
        }
    }

    /**
     * Controller 가 신규 생성 응답 (BookingResponse 직렬화) vs 캐시 응답
     * (raw JSON string) 을 구분해 처리할 수 있도록 표지.
     */
    public sealed interface BookingFlowResult {

        static BookingFlowResult fresh(BookingResponse response) {
            return new Fresh(response);
        }

        static BookingFlowResult cached(String responseJson) {
            return new Cached(responseJson != null ? responseJson : "");
        }

        record Fresh(BookingResponse response) implements BookingFlowResult {}
        record Cached(String responseJson) implements BookingFlowResult {}
    }
}

package com.booking.application;

import com.booking.api.booking.dto.BookingResponse;
import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyKeyRepository;
import com.booking.domain.idempotency.IdempotencyStatus;
import com.booking.domain.outbox.OutboxEvent;
import com.booking.domain.outbox.OutboxEventRepository;
import com.booking.domain.outbox.OutboxEventStatus;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentComposition;
import com.booking.domain.payment.PaymentRequest;
import com.booking.domain.payment.PaymentResult;
import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptRepository;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
import com.booking.domain.stock.StockRepository;
import com.booking.infrastructure.payment.PaymentRejectedException;
import com.booking.infrastructure.payment.PaymentTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * POST /booking 처리 흐름 (DECISIONS.md §11 / ADR-008 amendment / ADR-009 / ADR-010 / ADR-011).
 *
 * <p>흐름:
 * <ol>
 *   <li>body hash 계산</li>
 *   <li>idempotency check & reserve (Redis SETNX, ADR-006)</li>
 *   <li>stock atomic hold (Redis Lua, ADR-008/002)</li>
 *   <li>persistInitialState 트랜잭션 — booking HOLD INSERT + paymentAttempt INIT INSERT
 *       + CAS REQUESTED + booking PG_PENDING (ERD §6.1/6.2)</li>
 *   <li>PG 호출 (트랜잭션 밖, CRITICAL #1) — 결과별 분기:
 *       <ul>
 *         <li>2XX 성공 → finalizeSuccess 트랜잭션 (paymentAttempt SUCCESS + booking COMPLETED
 *             + idempotency_key save + outbox INSERT)</li>
 *         <li>4XX (PaymentRejectedException) → finalizeRejected (paymentAttempt FAILED + booking
 *             FAILED) + idempotency cleanup → 400 응답</li>
 *         <li>5XX/timeout (PaymentTimeoutException) → finalizeTimeout (paymentAttempt TIMEOUT
 *             + booking UNKNOWN) + idempotency PROCESSING 유지 (Reconciliation feature-007 영역)
 *             → 503 응답</li>
 *       </ul>
 *   </li>
 *   <li>finalizeSuccess 트랜잭션 commit 후 Redis idempotency complete (캐시 응답 set)</li>
 * </ol>
 *
 * <p>Out of scope (후속 feature):
 * <ul>
 *   <li>Saga 보상 (PG cancel 호출, DB 실패 시) — feature-005</li>
 *   <li>Outbox 폴러 + 컨슈머 — feature-005</li>
 *   <li>TTL 만료 sweeper — feature-006</li>
 *   <li>Reconciliation worker — feature-007</li>
 * </ul>
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final long IDEMPOTENCY_TTL_MINUTES = 15;
    private static final int STOCK_HOLD_TTL_SECONDS = 300;

    private final IdempotencyKeyService idempotencyKeyService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final BodyHashCalculator bodyHashCalculator;
    private final BookingRepository bookingRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ExternalPaymentMethod cardPayment;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public BookingService(IdempotencyKeyService idempotencyKeyService,
                          IdempotencyKeyRepository idempotencyKeyRepository,
                          BodyHashCalculator bodyHashCalculator,
                          BookingRepository bookingRepository,
                          PaymentAttemptRepository paymentAttemptRepository,
                          OutboxEventRepository outboxEventRepository,
                          ExternalPaymentMethod cardPayment,
                          StockRepository stockRepository,
                          ObjectMapper objectMapper,
                          TransactionTemplate transactionTemplate) {
        this.idempotencyKeyService = idempotencyKeyService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.bodyHashCalculator = bodyHashCalculator;
        this.bookingRepository = bookingRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.cardPayment = cardPayment;
        this.stockRepository = stockRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public BookingFlowResult create(UUID idempotencyKey, CreateBookingRequest request) {
        String bodyHash = bodyHashCalculator.calculate(request);

        IdempotencyCheckResult check = idempotencyKeyService.checkAndReserve(idempotencyKey, bodyHash);
        switch (check.type()) {
            case PROCESSING    -> throw new IdempotencyProcessingException();
            case HASH_MISMATCH -> throw new IdempotencyHashMismatchException();
            case COMPLETED     -> {
                return BookingFlowResult.cached(check.cachedResponse());
            }
            case NEW           -> { /* 진행 */ }
        }

        if (!stockRepository.tryHold(request.productId(), request.userId(), STOCK_HOLD_TTL_SECONDS)) {
            idempotencyKeyService.releaseKey(idempotencyKey);
            throw new StockSoldOutException();
        }

        // PaymentComposition 검증 (혼용 정책)
        new PaymentComposition(List.of(cardPayment));

        // 단계 4 — booking HOLD + paymentAttempt INIT/REQUESTED + booking PG_PENDING (트랜잭션 1)
        BookingPgState state = persistInitialState(idempotencyKey, request);

        // 단계 5 — PG 호출 (트랜잭션 밖)
        try {
            PaymentResult pgResult = cardPayment.execute(
                new PaymentRequest(request.amount(), state.attemptUuid().toString(), request.userId()));
            FinalizeOutcome outcome = finalizeSuccess(state, idempotencyKey, request, bodyHash, pgResult);
            // 트랜잭션 commit 후 Redis idempotency complete (캐시 갱신)
            idempotencyKeyService.complete(idempotencyKey, bodyHash, outcome.responseJson(), state.bookingId());
            return BookingFlowResult.fresh(outcome.response());
        } catch (PaymentRejectedException e) {
            finalizeRejected(state);
            idempotencyKeyService.releaseKey(idempotencyKey);
            throw e;
        } catch (PaymentTimeoutException e) {
            finalizeTimeout(state);
            // idempotency PROCESSING 유지 — Reconciliation worker (feature-007) 가 결과 확정 후
            // push 알림 또는 클라이언트 새로고침 폴링으로 결과 전달.
            throw e;
        }
    }

    private BookingPgState persistInitialState(UUID idempotencyKey, CreateBookingRequest request) {
        return transactionTemplate.execute(status -> persistInitialStateTx(idempotencyKey, request));
    }

    private BookingPgState persistInitialStateTx(UUID idempotencyKey, CreateBookingRequest request) {
        Instant now = Instant.now();
        String compositionSnapshot = composeSnapshot(request);

        Booking booking = new Booking(null, idempotencyKey, request.userId(), request.productId(),
            request.amount(), compositionSnapshot, BookingStatus.HOLD, now, now);
        Booking savedBooking = bookingRepository.save(booking);

        UUID attemptUuid = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt(null, attemptUuid, savedBooking.getId(),
            request.amount(), compositionSnapshot, PaymentAttemptStatus.INIT,
            null, null, now, now);
        PaymentAttempt savedAttempt = paymentAttemptRepository.save(attempt);

        int attemptCas = paymentAttemptRepository.casToRequested(savedAttempt.getId());
        if (attemptCas != 1) {
            throw new IllegalStateException(
                "PaymentAttempt CAS REQUESTED failed for id=" + savedAttempt.getId());
        }

        int bookingCas = bookingRepository.casToStatus(savedBooking.getId(),
            BookingStatus.HOLD, BookingStatus.PG_PENDING);
        if (bookingCas != 1) {
            throw new IllegalStateException(
                "Booking CAS PG_PENDING failed for id=" + savedBooking.getId());
        }

        return new BookingPgState(savedBooking.getId(), savedAttempt.getId(), attemptUuid);
    }

    private FinalizeOutcome finalizeSuccess(BookingPgState state, UUID idempotencyKey,
                                            CreateBookingRequest request, String bodyHash,
                                            PaymentResult pgResult) {
        return transactionTemplate.execute(
            status -> finalizeSuccessTx(state, idempotencyKey, request, bodyHash, pgResult));
    }

    private FinalizeOutcome finalizeSuccessTx(BookingPgState state, UUID idempotencyKey,
                                              CreateBookingRequest request, String bodyHash,
                                              PaymentResult pgResult) {
        paymentAttemptRepository.updateToTerminal(state.attemptId(),
            PaymentAttemptStatus.SUCCESS, pgResult.externalPaymentId());

        int cas = bookingRepository.casToStatus(state.bookingId(),
            BookingStatus.PG_PENDING, BookingStatus.COMPLETED);
        if (cas != 1) {
            throw new IllegalStateException(
                "Booking CAS COMPLETED failed for id=" + state.bookingId());
        }

        Instant now = Instant.now();
        IdempotencyKey ikRow = new IdempotencyKey(idempotencyKey, request.userId(), bodyHash,
            IdempotencyStatus.PROCESSING, null, null,
            now, now.plus(IDEMPOTENCY_TTL_MINUTES, ChronoUnit.MINUTES));
        idempotencyKeyRepository.save(ikRow);

        BookingResponse response = new BookingResponse(state.bookingId());
        String responseJson = serialize(response);

        // ADR-010 — booking save 와 같은 트랜잭션 안에서 outbox INSERT.
        // INSERT 실패 시 fallback 로깅 (트랜잭션 롤백 X — booking 보존 우선, ADR-010 #6).
        try {
            String outboxPayload = "{\"bookingId\":" + state.bookingId()
                + ",\"externalPaymentId\":\"" + pgResult.externalPaymentId() + "\"}";
            OutboxEvent event = new OutboxEvent(null, "BookingCompleted", idempotencyKey,
                outboxPayload, OutboxEventStatus.PENDING, now, null);
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("[OUTBOX_INSERT_FAILED] bookingId={} externalPaymentId={} message={}",
                state.bookingId(), pgResult.externalPaymentId(), e.getMessage(), e);
        }

        return new FinalizeOutcome(response, responseJson);
    }

    private void finalizeRejected(BookingPgState state) {
        transactionTemplate.executeWithoutResult(status -> {
            paymentAttemptRepository.updateToTerminal(state.attemptId(),
                PaymentAttemptStatus.FAILED, null);
            int cas = bookingRepository.casToStatus(state.bookingId(),
                BookingStatus.PG_PENDING, BookingStatus.FAILED);
            if (cas != 1) {
                log.warn("Booking CAS FAILED skipped (already terminal) for id={}", state.bookingId());
            }
        });
    }

    private void finalizeTimeout(BookingPgState state) {
        transactionTemplate.executeWithoutResult(status -> {
            paymentAttemptRepository.updateToTerminal(state.attemptId(),
                PaymentAttemptStatus.TIMEOUT, null);
            int cas = bookingRepository.casToStatus(state.bookingId(),
                BookingStatus.PG_PENDING, BookingStatus.UNKNOWN);
            if (cas != 1) {
                log.warn("Booking CAS UNKNOWN skipped (already terminal) for id={}", state.bookingId());
            }
        });
    }

    private String composeSnapshot(CreateBookingRequest request) {
        return "{\"methods\":[{\"type\":\"" + request.paymentMethod().toUpperCase()
            + "\",\"amount\":\"" + request.amount().toPlainString() + "\"}]}";
    }

    private String serialize(BookingResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("BookingResponse JSON 직렬화 실패", e);
        }
    }

    /**
     * persistInitialState 트랜잭션 commit 후 PG 호출에 전달할 state.
     */
    public record BookingPgState(long bookingId, long attemptId, UUID attemptUuid) {}

    /**
     * finalizeSuccess 트랜잭션 commit 후 호출자에게 전달할 outcome.
     */
    public record FinalizeOutcome(BookingResponse response, String responseJson) {}

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

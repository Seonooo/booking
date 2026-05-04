package com.booking.application;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.payment.PaymentStatusResult;
import com.booking.domain.payment_attempt.PaymentAttempt;
import com.booking.domain.payment_attempt.PaymentAttemptRepository;
import com.booking.domain.payment_attempt.PaymentAttemptStatus;
import com.booking.domain.stock.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PG 상태 조회 + booking UNKNOWN 결과 확정 — ADR-011 §결정 2.
 *
 * <p>흐름:
 * <ol>
 *   <li>{@link #reconcileBatch} — findStaleUnknown(now-30s, batchLimit) 으로 후보 조회 + 각각 {@link #reconcileOne}</li>
 *   <li>{@link #reconcileOne} — booking 조회 + cardPayment.queryStatus (트랜잭션 밖, CRITICAL #1) + 결과별 분기</li>
 *   <li>SUCCESS → paymentAttempt SUCCESS + booking COMPLETED CAS</li>
 *   <li>FAILED → paymentAttempt FAILED + booking FAILED CAS + stock.release</li>
 *   <li>NOT_FOUND → retry_count++ + last_reconcile_at NOW (UNKNOWN 유지)</li>
 *   <li>NOT_FOUND + retry_count >= 3 → log [RECONCILE_FAILED] 마커 (운영자 에스컬레이션, ADR-011 §핵심 원칙)</li>
 * </ol>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** ADR-011 — last_reconcile_at < 30s 이내 skip (in-flight 보호 + 중복 reconciliation 방지). */
    private static final int RECONCILE_THRESHOLD_SECONDS = 30;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int BATCH_LIMIT = 50;

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final BookingRepository bookingRepository;
    private final ExternalPaymentMethod cardPayment;
    private final StockRepository stockRepository;
    private final TransactionTemplate transactionTemplate;

    public ReconciliationService(PaymentAttemptRepository paymentAttemptRepository,
                                 BookingRepository bookingRepository,
                                 ExternalPaymentMethod cardPayment,
                                 StockRepository stockRepository,
                                 TransactionTemplate transactionTemplate) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.bookingRepository = bookingRepository;
        this.cardPayment = cardPayment;
        this.stockRepository = stockRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public void reconcileBatch() {
        Instant threshold = Instant.now().minusSeconds(RECONCILE_THRESHOLD_SECONDS);
        List<PaymentAttempt> stale = paymentAttemptRepository.findStaleUnknown(threshold, BATCH_LIMIT);
        if (stale.isEmpty()) {
            return;
        }
        log.info("[RECONCILE_BATCH_START] candidates={}", stale.size());
        for (PaymentAttempt attempt : stale) {
            try {
                reconcileOne(attempt);
            } catch (Exception e) {
                log.error("[RECONCILE_ONE_FAILED] attemptId={} message={}",
                    attempt.getId(), e.getMessage(), e);
            }
        }
    }

    public void reconcileOne(PaymentAttempt attempt) {
        Optional<Booking> bookingOpt = bookingRepository.findById(attempt.getBookingId());
        if (bookingOpt.isEmpty()) {
            log.warn("[RECONCILE_BOOKING_NOT_FOUND] attemptId={} bookingId={}",
                attempt.getId(), attempt.getBookingId());
            return;
        }
        Booking booking = bookingOpt.get();

        // PG 상태 조회 — 트랜잭션 밖 (CRITICAL #1)
        PaymentStatusResult result = cardPayment.queryStatus(
            attempt.getExternalPaymentId(), attempt.getAttemptId());

        switch (result.status()) {
            case SUCCESS -> handleSuccess(attempt, booking, result);
            case FAILED -> handleFailed(attempt, booking);
            case NOT_FOUND -> handleNotFound(attempt);
        }
    }

    private void handleSuccess(PaymentAttempt attempt, Booking booking, PaymentStatusResult result) {
        Integer cas = transactionTemplate.execute(status -> {
            paymentAttemptRepository.updateToTerminal(
                attempt.getId(), PaymentAttemptStatus.SUCCESS, result.externalPaymentId());
            BookingStatus current = booking.getStatus();
            if (current == BookingStatus.UNKNOWN || current == BookingStatus.PG_PENDING) {
                return bookingRepository.casToStatus(booking.getId(), current, BookingStatus.COMPLETED);
            }
            return 0;
        });
        if (cas != null && cas == 1) {
            log.info("[RECONCILE_SUCCESS] attemptId={} bookingId={}",
                attempt.getId(), booking.getId());
        }
    }

    private void handleFailed(PaymentAttempt attempt, Booking booking) {
        Integer cas = transactionTemplate.execute(status -> {
            paymentAttemptRepository.updateToTerminal(
                attempt.getId(), PaymentAttemptStatus.FAILED, attempt.getExternalPaymentId());
            BookingStatus current = booking.getStatus();
            if (current == BookingStatus.UNKNOWN || current == BookingStatus.PG_PENDING) {
                return bookingRepository.casToStatus(booking.getId(), current, BookingStatus.FAILED);
            }
            return 0;
        });
        if (cas != null && cas == 1) {
            // CAS 통과 — stock release (트랜잭션 밖, CRITICAL #1)
            stockRepository.release(booking.getAccommodationId(), booking.getUserId());
            log.info("[RECONCILE_FAILED_RESULT] attemptId={} bookingId={} stockReleased=true",
                attempt.getId(), booking.getId());
        }
    }

    private void handleNotFound(PaymentAttempt attempt) {
        if (attempt.getReconcileRetryCount() >= MAX_RETRY_COUNT) {
            // ADR-011 §핵심 원칙 — retry 소진만으로 FAILED 전이 금지. UNKNOWN 유지 + 운영자 에스컬레이션.
            log.error("[RECONCILE_FAILED] attemptId={} bookingId={} retryCount={} (UNKNOWN 유지, 운영자 수동 개입 필요)",
                attempt.getId(), attempt.getBookingId(), attempt.getReconcileRetryCount());
        }
        // last_reconcile_at NOW + retry_count++ — escalation 후에도 다음 cycle 진입 차단 (30s threshold)
        transactionTemplate.executeWithoutResult(status ->
            paymentAttemptRepository.incrementRetryCount(attempt.getId()));
        log.info("[RECONCILE_NOT_FOUND] attemptId={} retryCount(after)={}",
            attempt.getId(), attempt.getReconcileRetryCount() + 1);
    }
}

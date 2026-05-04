package com.booking.application;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.payment.ExternalPaymentMethod;
import com.booking.domain.stock.StockRepository;
import com.booking.infrastructure.payment.PgCancelFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Saga 보상 (ADR-009 §Saga / DECISIONS.md §11 케이스 3).
 *
 * <p>BookingService.create 의 finalizeSuccess 트랜잭션 commit 실패 시 호출 — *PG 청구됨 +
 * DB 미생성* 상태. PG cancel API 로 청구 취소 + booking PG_PENDING → FAILED CAS + stock release.
 *
 * <p>흐름:
 * <ol>
 *   <li>cardPayment.cancel(externalPaymentId, amount) — PG 측 청구 취소</li>
 *   <li>실패 시 [SAGA_COMPENSATION_PENDING] log + Outbox 폴러가 후속 재시도 (ADR-010, future feature)</li>
 *   <li>booking PG_PENDING → FAILED CAS</li>
 *   <li>stock release (Redis Lua, ADR-008 amendment 의 release Lua)</li>
 * </ol>
 *
 * <p>본 PR scope 단순화 — Outbox 보상 payload INSERT 는 future feature (Outbox 재시도 본격).
 * 본 PR 은 *동기 보상* 만 — PG cancel 1회 시도. 실패 시 fallback 로깅.
 */
@Service
public class SagaCompensationService {

    private static final Logger log = LoggerFactory.getLogger(SagaCompensationService.class);

    private final ExternalPaymentMethod cardPayment;
    private final BookingRepository bookingRepository;
    private final StockRepository stockRepository;

    public SagaCompensationService(ExternalPaymentMethod cardPayment,
                                   BookingRepository bookingRepository,
                                   StockRepository stockRepository) {
        this.cardPayment = cardPayment;
        this.bookingRepository = bookingRepository;
        this.stockRepository = stockRepository;
    }

    public void compensate(String externalPaymentId, BigDecimal amount, long bookingId) {
        // 1. PG cancel 시도
        try {
            cardPayment.cancel(externalPaymentId, amount.longValueExact());
        } catch (PgCancelFailedException e) {
            // PG cancel 실패 — fallback 로깅. Outbox 재시도 (future) 영역.
            log.error("[SAGA_COMPENSATION_PENDING] bookingId={} externalPaymentId={} (PG cancel 실패, 운영자 수동 개입 또는 Outbox 재시도 필요)",
                bookingId, externalPaymentId);
            // PG cancel 실패해도 booking / stock 처리는 진행 (booking 보존 우선)
        }

        // 2. booking PG_PENDING → FAILED CAS
        var bookingOpt = bookingRepository.findById(bookingId);
        if (bookingOpt.isEmpty()) {
            log.warn("[SAGA_COMPENSATION_BOOKING_NOT_FOUND] bookingId={}", bookingId);
            return;
        }
        Booking booking = bookingOpt.get();
        int cas = bookingRepository.casToStatus(bookingId, BookingStatus.PG_PENDING, BookingStatus.FAILED);
        if (cas != 1) {
            log.warn("[SAGA_COMPENSATION_BOOKING_CAS_SKIP] bookingId={} status={} (이미 다른 worker 가 처리)",
                bookingId, booking.getStatus());
            return;
        }

        // 3. stock release (Redis Lua atomic)
        stockRepository.release(booking.getAccommodationId(), booking.getUserId());
        log.info("[SAGA_COMPENSATION_DONE] bookingId={} externalPaymentId={}", bookingId, externalPaymentId);
    }
}

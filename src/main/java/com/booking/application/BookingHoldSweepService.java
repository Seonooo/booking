package com.booking.application;

import com.booking.domain.booking.Booking;
import com.booking.domain.booking.BookingRepository;
import com.booking.domain.booking.BookingStatus;
import com.booking.domain.stock.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * booking HOLD/PG_PENDING 5분 + PG 유예 60초 초과 sweep — ADR-008 amendment 정합.
 *
 * <p>sweepBatch 가 stale 후보 조회 + sweepOne 호출. sweepOne 이 단일 booking 의 CAS 흐름:
 * <ol>
 *   <li>findById 로 current status 조회</li>
 *   <li>HOLD/PG_PENDING 만 진입 — 다른 status 는 이미 종결 처리, return</li>
 *   <li>casToStatus(current → FAILED) — ROW_COUNT==1 일 때만 stock.release 호출 (over-INCR 차단)</li>
 * </ol>
 *
 * <p>동시성 — 두 thread 동시 sweepOne 호출 시 한 thread CAS row_count==1 + stock release,
 * 다른 thread row_count==0 + skip. CAS UPDATE 단일 statement 의 atomicity 활용.
 */
@Service
public class BookingHoldSweepService {

    private static final Logger log = LoggerFactory.getLogger(BookingHoldSweepService.class);

    /**
     * HOLD 5분 (300s) + PG 유예 60초 = 360초. PG_PENDING 정확 처리 (60초 유예 후 reconciliation
     * 진입) 는 feature-007 영역 — 본 sweeper 는 단일 threshold 로 단순화.
     */
    private static final int TTL_THRESHOLD_SECONDS = 360;
    private static final int BATCH_LIMIT = 100;

    private final BookingRepository bookingRepository;
    private final StockRepository stockRepository;

    public BookingHoldSweepService(BookingRepository bookingRepository,
                                   StockRepository stockRepository) {
        this.bookingRepository = bookingRepository;
        this.stockRepository = stockRepository;
    }

    public void sweepBatch() {
        Instant threshold = Instant.now().minusSeconds(TTL_THRESHOLD_SECONDS);
        List<Booking> stale = bookingRepository.findStaleByStatusBatch(threshold, BATCH_LIMIT);
        if (stale.isEmpty()) {
            return;
        }
        log.info("[SWEEP_BATCH_START] candidates={}", stale.size());
        for (Booking booking : stale) {
            try {
                sweepOne(booking.getId());
            } catch (Exception e) {
                log.error("[SWEEP_ONE_FAILED] bookingId={} message={}",
                    booking.getId(), e.getMessage(), e);
            }
        }
    }

    public void sweepOne(long bookingId) {
        Optional<Booking> opt = bookingRepository.findById(bookingId);
        if (opt.isEmpty()) {
            log.warn("[SWEEP_BOOKING_NOT_FOUND] bookingId={}", bookingId);
            return;
        }
        Booking booking = opt.get();
        BookingStatus current = booking.getStatus();

        int rowCount;
        if (current == BookingStatus.HOLD) {
            rowCount = bookingRepository.casToStatus(bookingId, BookingStatus.HOLD, BookingStatus.FAILED);
        } else if (current == BookingStatus.PG_PENDING) {
            rowCount = bookingRepository.casToStatus(bookingId, BookingStatus.PG_PENDING, BookingStatus.FAILED);
        } else {
            // 이미 종결 처리 (COMPLETED / FAILED / UNKNOWN) — sweep skip
            log.debug("[SWEEP_SKIP_NON_TRANSIENT] bookingId={} status={}", bookingId, current);
            return;
        }

        if (rowCount == 1) {
            // CAS 통과 — stock release (over-INCR 방지: 다른 thread CAS row_count==0 시 미호출)
            stockRepository.release(booking.getAccommodationId(), booking.getUserId());
            log.info("[SWEEP_DONE] bookingId={} accommodation={} user={} from={}",
                bookingId, booking.getAccommodationId(), booking.getUserId(), current);
        } else {
            log.debug("[SWEEP_CAS_RACE] bookingId={} expected={} (다른 worker / Reconciliation 가 이미 처리)",
                bookingId, current);
        }
    }
}

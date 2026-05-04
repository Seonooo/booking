package com.booking.infrastructure.scheduler;

import com.booking.application.BookingHoldSweepService;
import org.springframework.stereotype.Component;

/**
 * @Scheduled + @SchedulerLock booking HOLD sweeper — Phase 3.4 GREEN 에서 본격 구현.
 *
 * <p>Phase 2 RED stub — Bean 등록만. @Scheduled annotation 미부여라 자동 trigger X.
 */
@Component
public class BookingHoldSweeper {

    private final BookingHoldSweepService sweepService;

    public BookingHoldSweeper(BookingHoldSweepService sweepService) {
        this.sweepService = sweepService;
    }
}

package com.booking.api.booking;

import com.booking.api.booking.dto.CreateBookingRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 2 RED stub — Phase 3 GREEN에서 본격 구현.
 * POST /booking 진입점. 멱등성 키 헤더 수신 + 3-state 분기 (ADR-006).
 * PG 호출은 @Transactional 밖 (CLAUDE.md CRITICAL #1, ADR-009).
 */
@RestController
@RequestMapping("/booking")
public class BookingController {

    /**
     * POST /booking
     * 단계 1: 멱등성 체크 (@Transactional 밖) — Phase 1 blueprint §4.
     * Phase 3에서 본격 구현.
     */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String rawKey,
            @RequestBody CreateBookingRequest request) {
        throw new UnsupportedOperationException("Phase 2 RED stub");
    }
}

package com.booking.api.booking;

import com.booking.api.booking.dto.CreateBookingRequest;
import com.booking.application.BookingService;
import com.booking.application.BookingService.BookingFlowResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * POST /booking — 멱등성 처리 진입 (ADR-006).
 *
 * <p>Bean Validation 활성: {@code @Valid} 가 {@link CreateBookingRequest} 의
 * 필드 제약을 검사 → 실패 시 GlobalExceptionHandler 가 400 으로 변환.
 */
@RestController
@RequestMapping("/booking")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {

        BookingFlowResult result = bookingService.create(idempotencyKey, request);
        return switch (result) {
            case BookingFlowResult.Fresh fresh ->
                ResponseEntity.ok(fresh.response());
            case BookingFlowResult.Cached cached ->
                ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cached.responseJson());
        };
    }
}

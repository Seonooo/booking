package com.booking.api.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * POST /booking 요청 DTO (ADR-006 §5 body hash 입력).
 *
 * <p>Bean Validation: 본 PR Phase 3.4 에서 활성. 검증 실패 시
 * {@link org.springframework.web.bind.MethodArgumentNotValidException} → 400
 * (GlobalExceptionHandler).
 */
public record CreateBookingRequest(
        @Positive long userId,
        @Positive long productId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String paymentMethod,
        @PositiveOrZero long points
) {
}

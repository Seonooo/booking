package com.booking.api.checkout;

import com.booking.api.checkout.dto.CheckoutResponse;
import com.booking.application.CheckoutService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /checkout — 주문서 진입 (REQUIREMENTS §1.1).
 *
 * <p>예외 매핑은 {@link com.booking.api.GlobalExceptionHandler} 에 통합 —
 * {@link com.booking.application.AccommodationNotFoundException} → 404.
 */
@RestController
@RequestMapping("/checkout")
@Validated
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @GetMapping
    public ResponseEntity<CheckoutResponse> get(
            @RequestParam("productId") @Positive long productId,
            @RequestParam("userId") @Positive long userId) {
        CheckoutResponse body = checkoutService.get(productId, userId);
        return ResponseEntity.ok(body);
    }
}

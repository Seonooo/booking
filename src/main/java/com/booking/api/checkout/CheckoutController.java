package com.booking.api.checkout;

import com.booking.api.checkout.dto.CheckoutResponse;
import com.booking.application.AccommodationNotFoundException;
import com.booking.application.CheckoutService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /checkout — 주문서 진입 (REQUIREMENTS §1.1).
 *
 * <p>본 PR 은 main 기준 — feature-001 의 GlobalExceptionHandler 미반영
 * 상태에서 작업하므로 Controller-local {@code @ExceptionHandler} 로 404 매핑만
 * 적용. 두 PR 모두 main 으로 merge 되면 GlobalExceptionHandler 로 통합 검토.
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

    @ExceptionHandler(AccommodationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(AccommodationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("message", "존재하지 않는 상품입니다"));
    }
}

package com.booking.application;

import com.booking.api.booking.dto.CreateBookingRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SHA-256(userId|productId|amount|paymentMethod|points) 계산 (ADR-006 §5).
 *
 * <p>입력 정규화:
 * <ul>
 *   <li>{@code amount.toPlainString()} — 1E+5 같은 지수 표기 방지</li>
 *   <li>{@code paymentMethod.toUpperCase()} — 대소문자 정규화</li>
 *   <li>{@code |} 구분자 — userId/productId 같은 인접 정수 필드의 ambiguity 방지
 *       (예: 1|23 vs 12|3 — 동일 hash 위험 차단)</li>
 * </ul>
 * 출력: lowercase hex 64자 (Java 17+ {@link HexFormat#formatHex(byte[])}).
 */
public class BodyHashCalculator {

    private static final String ALGORITHM = "SHA-256";
    private static final String DELIMITER = "|";

    public String calculate(CreateBookingRequest request) {
        Objects.requireNonNull(request, "request");

        String input = request.userId()
            + DELIMITER + request.productId()
            + DELIMITER + request.amount().toPlainString()
            + DELIMITER + request.paymentMethod().toUpperCase()
            + DELIMITER + request.points();

        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 JDK 표준 — 실제 발생 불가
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

package com.booking.application;

/**
 * 존재하지 않는 productId 조회 — Controller 가 HTTP 404 로 변환.
 */
public class AccommodationNotFoundException extends RuntimeException {

    public AccommodationNotFoundException(long productId) {
        super("accommodation not found: productId=" + productId);
    }
}

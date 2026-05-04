package com.booking.infrastructure.payment;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 외부 PG 호출용 {@link RestTemplate} bean 등록.
 *
 * <p>본 PR 은 timeout 만 적용 — 향후 (Saga, Reconciliation) interceptor /
 * connection pool / retry 정책은 별도 config 분리 검토.
 */
@Configuration
public class PaymentConfig {

    @Bean
    public RestTemplate pgRestTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(10))
            .build();
    }
}

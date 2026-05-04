package com.booking.infrastructure.payment;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 외부 PG 호출용 {@link RestTemplate} bean 등록.
 *
 * <p>Spring Boot 3.5+ 의 {@code RestTemplateBuilder} 는 Java 21 환경에서 JDK
 * HttpClient (HTTP/2 지원) 를 default 로 사용한다. WireMock (테스트 / 일부
 * 스테이징) 은 HTTP/1.1 전용이라 HTTP/2 negotiation 실패 시 RST_STREAM 으로
 * 호출이 cancel 된다. 본 PR 은 호환성 우선으로 {@link
 * SimpleClientHttpRequestFactory} (HttpURLConnection, HTTP/1.1) 를 명시 사용.
 *
 * <p>본 PR 은 timeout 만 적용 — 향후 (Saga, Reconciliation) interceptor /
 * connection pool / retry 정책은 별도 config 분리 검토.
 */
@Configuration
public class PaymentConfig {

    @Bean
    public RestTemplate pgRestTemplate(RestTemplateBuilder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return builder.requestFactory(() -> factory).build();
    }
}

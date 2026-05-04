# 선착순 숙박 예약 시스템 — 아키텍처 문서

> ADR 문서들을 기반으로 작성된 구현 기준 문서. 각 결정의 근거 ADR을 괄호로 표기.
> 미결정 영역은 "ADR 미결정"으로 표시.

---

## 1. 시스템 개요

자정에 오픈되는 한정 수량 숙박 상품의 실시간 선착순 예약 시스템. (DECISIONS.md)

| 항목 | 값 |
|------|-----|
| 평시 트래픽 | 50 TPS |
| 자정 burst (관측) | 1~5분간 500~1000 TPS 변동 |
| 설계 capacity 기준 | 1000 TPS 상한 (race / circuit breaker / pool sizing 분석은 이 상한 기준) |
| 재고 | 10개 한정 |
| 결제 수단 | 신용카드, Y페이, Y포인트 (복합 결제 일부 가능) |
| 핵심 SLO | **Fairness 100% > Availability 99.9%** |
| 인프라 | Spring Boot, Redis Sentinel HA, MySQL 8.0+, 외부 PG (mock) |
| 배포 환경 | 다중 인스턴스 수평 확장 (4~5대 + LB), stateless |

**SLO 설명**: Fairness 100%는 모든 Fail-Closed 결정의 근거이다. Redis 장애 시 Fail-Open으로 통과시키면 봇/매크로 진입, 초과 판매, 이중 결제가 발생할 수 있으며 이는 무결성 영구 훼손이다. 503 응답으로 인한 가용성 저하는 재시도로 복구 가능하지만 무결성 훼손은 불가능하다. (ADR-007)

---

## 2. 레이어 구조

본 시스템은 **헥사고날 (Ports & Adapters) 아키텍처**를 채택한다 (ADR-014). 4 레이어는 driving adapter / use case / domain + driven port / driven adapter 매핑.

```
┌──────────────────────────────────────────────────────────┐
│  api                — Driving adapters (HTTP)            │
│  Controller / DTO / GlobalExceptionHandler               │
├──────────────────────────────────────────────────────────┤
│  application        — Use Cases                          │
│  Service (트랜잭션 경계) — PG 호출은 반드시 트랜잭션 밖   │
├──────────────────────────────────────────────────────────┤
│  domain             — Domain models + Driven ports       │
│  도메인 모델 / Value Object / Repository 인터페이스 (port) │
├──────────────────────────────────────────────────────────┤
│  infrastructure     — Driven adapters                    │
│  Redis Lua Script / PG Client(mock) / Outbox Poller     │
│  Resilience4j 설정                                       │
└──────────────────────────────────────────────────────────┘
```

**의존성 방향** (헥사고날 핵심): driving adapter → use case → driven port (domain interface) ← driven adapter (infrastructure 구현체). 도메인은 외부 기술에 의존하지 않는다.

### api 레이어 (Driving adapters)

- **책임**: HTTP 요청/응답 변환, DTO 바인딩, 예외 → HTTP 상태코드 변환
- **규칙**:
  - DTO는 도메인 모델로 변환 후 application 레이어로 전달
  - `InvalidPaymentCompositionException` 등 도메인 예외를 사용자 친화 에러로 변환 (ADR-009)
  - 멱등성 응답 코드 3-state: `200` (캐시 완료) / `409` (처리 중) / `422` (payload 불일치) (ADR-006)

### application 레이어 (Use Cases)

- **책임**: 유스케이스 조율 (driving port 구현 + driven port 호출), 트랜잭션 경계 정의
- **규칙**:
  - **PG 호출은 반드시 `@Transactional` 밖에서 수행** (ADR-009)
  - DB 트랜잭션은 Booking + Payment + Outbox INSERT만 포함
  - Outbox INSERT 실패 시 트랜잭션 롤백하지 않고 fallback 로깅만 수행 (ADR-010)

### domain 레이어 (Domain models + Driven ports)

- **책임**: 도메인 모델 정의, 비즈니스 불변식 검증, **Driven port 인터페이스 선언**
- **규칙**:
  - `PaymentComposition` 생성자에서 혼용 정책 검증 — 외부 결제 수단 1개 초과 불가 (ADR-009)
  - Driven port (Repository, EventPublisher, ExternalPaymentMethod 등) 인터페이스는 domain에 선언, 구현(driven adapter)은 infrastructure에 위치 (ADR-014)
  - **외부 기술 의존성 0** — JPA annotation, Spring `@Component`, Redis 클라이언트 등 import 금지 (ADR-014)

### infrastructure 레이어 (Driven adapters)

- **책임**: 외부 시스템 연동 구현 (Redis, DB, PG, 스케줄러) — domain의 driven port를 implement
- **규칙**:
  - 모든 Redis "검사 + 변경" 연산은 Lua Script로 atomic 처리 (ADR-002)
  - 모든 Redis 의존 컴포넌트에 Resilience4j Circuit Breaker 적용, fallback은 503 반환 (ADR-007)
  - Outbox 폴러는 다중 인스턴스 환경에서 분산 락 필수 (`ShedLock` 또는 `SELECT FOR UPDATE SKIP LOCKED`) (ADR-010, DECISIONS.md)

---

## 3. 패키지 구조

ADR-014 헥사고날 매핑: api = driving adapter / application = use case / domain = domain models + driven port / infrastructure = driven adapter.

**※ ADR-009의 결제 수단 구현체(`CardPayment`, `YPayPayment`, `PointPayment`)는 본래 의미상 driven adapter라 ADR-014 정합 원칙으로는 `infrastructure/payment/` 가 맞다. 그러나 본 ARCHITECTURE.md 원문은 ADR-009 작성 시점의 결정을 기록한 것이고 본 문서는 헥사고날 라벨링만 추가한다. 실제 패키지 위치는 첫 코드 작성 시점에 ADR-014 기준으로 재배치 검토.**

```
com.booking/
├── api/                                # Driving adapters (HTTP)
│   ├── BookingController              # POST /booking, GET /checkout
│   ├── AdminController                # Circuit Breaker 강제 OPEN/CLOSE, Saga 수동 재시도
│   ├── dto/
│   │   ├── BookingRequest             # idempotency_key, userId, productId, amount, paymentMethod, points
│   │   └── BookingResponse            # bookingId, status, paymentKey
│   └── GlobalExceptionHandler         # InvalidPaymentCompositionException → 400, 도메인 예외 → HTTP 변환
│
├── application/                        # Use Cases (driving port 구현)
│   ├── BookingService                 # 핵심 예약 흐름 조율 (ADR-009 Saga 순서)
│   ├── RateLimitService               # userId Token Bucket 검사 (ADR-005)
│   ├── StockService                   # 재고 DECR/INCR (ADR-008)
│   ├── IdempotencyService             # Redis SETNX + DB unique 이중 계층 (ADR-006)
│   └── OutboxPublishService           # Outbox 폴링 + 재발송 스케줄러 (ADR-010)
│
├── domain/                             # Domain models + Driven ports (interfaces)
│   ├── booking/
│   │   ├── Booking                    # 도메인 모델 (HOLD → PG_PENDING → COMPLETED/FAILED)
│   │   └── BookingRepository          # Driven port (persistence)
│   ├── payment/
│   │   ├── PaymentMethod              # 최상위 driven port (ADR-009)
│   │   ├── ExternalPaymentMethod      # 외부 PG 호출 driven port (ADR-009)
│   │   ├── InternalPaymentMethod      # 내부 DB 차감 driven port (ADR-009)
│   │   ├── CardPayment                # ※ driven adapter — ADR-014 기준 infrastructure/payment/ 권장
│   │   ├── YPayPayment                # ※ driven adapter — 위와 동일
│   │   ├── PointPayment               # ※ driven adapter — 위와 동일
│   │   ├── PaymentComposition         # Value Object, 생성자 불변식 검증 (ADR-009)
│   │   └── PaymentRepository          # Driven port (persistence)
│   ├── outbox/
│   │   ├── OutboxEvent                # id, event_type, idempotency_key, payload, status, created_at (ADR-010)
│   │   └── OutboxRepository           # Driven port (persistence)
│   └── event/
│       └── EventPublisher             # Driven port — 이벤트 발행 (ADR-010)
│
└── infrastructure/                     # Driven adapters
    ├── redis/                          # Redis driven adapters
    │   ├── StockLuaScript             # 재고 DECR Lua Script (ADR-008, ADR-002)
    │   ├── RateLimitLuaScript         # Token Bucket Lua Script (ADR-005, ADR-002)
    │   └── IdempotencyLuaScript       # 조회+상태비교+SETNX atomic Lua Script (ADR-006, ADR-002)
    ├── pg/                             # PG driven adapter
    │   └── MockPgClient               # ExternalPaymentMethod 구현체용 mock (DECISIONS.md)
    ├── persistence/                    # JPA driven adapters
    │   ├── BookingJpaRepository
    │   ├── PaymentJpaRepository
    │   └── OutboxJpaRepository
    ├── event/                          # Event publisher driven adapter
    │   └── InProcessEventPublisher    # EventPublisher 구현체 (ADR-010)
    └── resilience/                     # Cross-cutting (driven adapter 보호)
        └── ResilienceConfig           # Resilience4j Circuit Breaker + Bulkhead 설정 (ADR-007)
```

---

## 4. 핵심 인터페이스 & 클래스 설계

### 4.1 결제 수단 계층 (ADR-009 / ADR-014 driven port)

```java
// 최상위 — 모든 결제 수단의 공통 표지 (driven port — domain interface)
public interface PaymentMethod {
    String getMethodType();
}

// 외부 PG API 호출 driven port — 보상 트랜잭션(취소 API) 포함
public interface ExternalPaymentMethod extends PaymentMethod {
    PaymentResult execute(PaymentRequest request);
    void cancel(String paymentKey, long cancelAmount);  // Saga 보상
}

// 내부 DB 차감 책임 — DB 트랜잭션 롤백으로 보상
public interface InternalPaymentMethod extends PaymentMethod {
    void execute(long userId, long amount);  // @Transactional 컨텍스트 내에서 호출
}

// 구현체
public class CardPayment implements ExternalPaymentMethod { ... }
public class YPayPayment implements ExternalPaymentMethod { ... }
public class PointPayment implements InternalPaymentMethod { ... }
```

**OCP 보장**: 카카오페이 추가 시 `KakaoPayPayment implements ExternalPaymentMethod` 파일 1개만 추가. `BookingService`, `PaymentComposition` 수정 불필요 (혼용 정책이 새로 생기지 않는 한). (ADR-009)

### 4.2 PaymentComposition Value Object (ADR-009)

```java
public class PaymentComposition {
    private final List<PaymentMethod> methods;

    public PaymentComposition(List<PaymentMethod> methods) {
        validate(methods);
        this.methods = List.copyOf(methods);  // 불변
    }

    private void validate(List<PaymentMethod> methods) {
        long externalCount = methods.stream()
            .filter(m -> m instanceof ExternalPaymentMethod)
            .count();
        if (externalCount > 1) {
            throw new InvalidPaymentCompositionException(
                "외부 결제 수단(카드/Y페이)은 동시 사용 불가");
        }
        if (methods.isEmpty()) {
            throw new InvalidPaymentCompositionException("결제 수단이 없습니다");
        }
    }

    public List<ExternalPaymentMethod> getExternalMethods() { ... }
    public List<InternalPaymentMethod> getInternalMethods() { ... }
}
```

검증은 수단 이름 비교가 아닌 타입 계층 비교로 작성 — 카카오페이 추가 시 자동 적용. (ADR-009)

### 4.3 EventPublisher 인터페이스 (ADR-010 / ADR-014 driven port)

```java
// Driven port — domain이 이벤트 발행을 위임하는 인터페이스
public interface EventPublisher {
    void publish(OutboxEvent event);
}

// 현재 구현체 (in-process, 50 TPS 환경)
@Component
public class InProcessEventPublisher implements EventPublisher {
    private final ApplicationEventPublisher springPublisher;

    @Override
    public void publish(OutboxEvent event) {
        springPublisher.publishEvent(event.toDomainEvent());
    }
}

// 향후 Kafka 도입 시: KafkaEventPublisher implements EventPublisher 추가,
// InProcessEventPublisher는 제거 또는 Profile 분리
```

### 4.4 Resilience4j 설정값 (ADR-007)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redisOps:
        slidingWindowType: TIME_BASED
        slidingWindowSize: 5           # 5초 윈도우
        minimumNumberOfCalls: 50       # 통계 최소 표본
        failureRateThreshold: 50       # 50% 실패율 → OPEN
        slowCallDurationThreshold: 1000  # 1초 이상 → slow call
        slowCallRateThreshold: 50      # 50% slow → OPEN
        waitDurationInOpenState: 5000  # OPEN 후 5초 대기
        permittedNumberOfCallsInHalfOpenState: 10
        automaticTransitionFromOpenToHalfOpenEnabled: true

  bulkhead:
    instances:
      redisOps:
        maxConcurrentCalls: 100        # 톰캣 스레드 풀 200의 50%
        maxWaitDuration: 0ms           # 대기 없이 즉시 거절

# Sentinel (인프라 설정 참고값)
# down-after-milliseconds: 5000
# failover-timeout: 15000
# quorum: 2
# min-replicas-to-write: 1    # split-brain 방어 필수
```

**모든 Redis 의존 컴포넌트의 fallback은 503 반환** (Fail-Closed 통일, ADR-007):

```java
@CircuitBreaker(name = "redisOps", fallbackMethod = "redisFallback")
@Bulkhead(name = "redisOps")
public boolean checkRateLimit(String userId) { ... }

private boolean redisFallback(String userId, Exception e) {
    throw new ServiceUnavailableException("일시적 장애로 요청을 처리할 수 없습니다.");
}
```

---

## 5. 핵심 요청 흐름

`POST /booking` 요청의 단계별 처리:

```
1. [Rate Limit 체크] (ADR-005)
   - Redis Lua Script (Token Bucket): HMGET + 산술 + HSET (atomic)
   - key: rate:user:{userId}, TTL: 60초
   - 초과 시 → 429, Redis 장애 시 → 503 (Fail-Closed)

2. [재고 DECR] (ADR-008, ADR-002)
   - Redis Lua Script: GET(검사) + DECR + SET hold key (atomic)
   - key: stock:accommodation:{id}
   - hold key: hold:user:{userId}:product:{productId}, TTL: 300초(5분)
   - 재고 0 → 200 + "매진" 응답, Redis 장애 → 503

3. [멱등성 키 체크] (ADR-006, ADR-002)
   - Redis Lua Script (check-then-act atomic): 조회 + 상태비교 + SETNX를 단일 스크립트로 처리
   - key: idempotency:{idempotencyKey}, TTL: 900초(15분)
   - 키 없음 → "PROCESSING" 상태로 SETNX
   - 키 존재 시: body hash 비교
     - 일치 + PROCESSING → 409 (처리 중)
     - 일치 + COMPLETED → 200 + 캐시 응답
     - 불일치 → 422 (payload 불일치)
   - ⚠️ SETNX 단독 사용 금지 — 조회+비교+저장이 분리되면 race condition 발생 (ADR-002)

4. [PaymentComposition 생성 및 검증] (ADR-009)
   - new PaymentComposition(methods) → 생성자에서 불변식 검증
   - 외부 결제 수단 2개 이상 → InvalidPaymentCompositionException → 400

5. [PG 호출] — 트랜잭션 밖 (ADR-009)
   - ExternalPaymentMethod.execute() → PG API 호출 (동기, timeout 10초)
   - 실패 → BookingException 던짐, DB 변경 없음, 보상 불필요
   - booking 상태: HOLD → PG_PENDING

6. [DB 트랜잭션] (ADR-009, ADR-010)
   @Transactional {
     - InternalPaymentMethod.execute() → user_points.balance 차감 (포인트 사용 시)
     - booking INSERT
     - payment INSERT
     - outbox INSERT (status: PENDING, idempotency_key 포함)
   } COMMIT
   - booking 상태: PG_PENDING → COMPLETED
   - Redis 멱등성 키 상태: PROCESSING → COMPLETED

7. [Outbox INSERT 실패 시 fallback] (ADR-010)
   - outbox INSERT 실패해도 트랜잭션 롤백하지 않음 (가용성 우선)
   - fallbackLogger.error("[OUTBOX_INSERT_FAILED]", event, e) 로깅
   - booking은 보존, 누락 이벤트는 운영 복구 절차로 처리

8. [Outbox 폴러 → EventPublisher.publish()] (ADR-010)
   - @Scheduled(fixedDelay = 5000): PENDING 이벤트 조회 → EventPublisher.publish()
   - 다중 인스턴스: 분산 락 필수 (ShedLock 또는 SELECT FOR UPDATE SKIP LOCKED)
   - publish() 실패 시 다음 주기에 재시도 (로그 기록)
   - @Scheduled(cron = "0 */5 * * * *"): 10분 이상 미발행 이벤트 재발송 배치
```

**TTL 만료 분기 처리** (ADR-003, ADR-008):

```
5분 TTL 도달:
  - 상태 HOLD → 즉시 재고 INCR
  - 상태 PG_PENDING → 60초 추가 유예

6분 도달 (PG 유예 만료):
  - 여전히 PG_PENDING → 강제 재고 INCR + PG 취소 API 호출 (Saga 보상)
```

> 주의: TTL 만료 시 무조건 INCR하면 결제 진행 중 사용자의 재고를 빼앗는 "PG 청구됨 + 예약 없음" 버그 발생.

**DB 실패 시 Saga 보상** (ADR-009, ADR-010):
DB 트랜잭션 실패 시 Outbox의 `compensation_payload`를 사용해 PG 취소 API 호출. 취소 호출 실패 시 Outbox 재시도로 보장.

---

## 6. DB 스키마

### booking

```sql
CREATE TABLE booking (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    idempotency_key VARCHAR(36)  NOT NULL,   -- UUID v4 (ADR-006)
    status          VARCHAR(20)  NOT NULL,   -- HOLD / PG_PENDING / COMPLETED / FAILED (ADR-008)
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 필수 인덱스 (ADR-006, DECISIONS.md)
CREATE UNIQUE INDEX ux_booking_idempotency_key ON booking (idempotency_key);
CREATE INDEX ix_booking_user_id ON booking (user_id);
```

### payment

```sql
CREATE TABLE payment (
    id                BIGSERIAL PRIMARY KEY,
    booking_id        BIGINT       NOT NULL REFERENCES booking(id),
    payment_key       VARCHAR(100),          -- PG 발급 결제 키 (Saga 보상에 사용, ADR-009)
    method_type       VARCHAR(20)  NOT NULL, -- CARD / YPAY / POINT
    amount            BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL, -- PENDING / COMPLETED / CANCELLED
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

### outbox

```sql
CREATE TABLE outbox (
    id                BIGSERIAL PRIMARY KEY,
    event_type        VARCHAR(100) NOT NULL,  -- e.g., "BookingCompleted" (ADR-010)
    idempotency_key   VARCHAR(36)  NOT NULL,  -- ADR-006 멱등키 그대로 사용 (ADR-010)
    payload           TEXT         NOT NULL,  -- JSON
    compensation_payload TEXT,                -- PG 취소에 필요한 paymentKey, amount 등 (ADR-009)
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP
);

-- 필수 인덱스 (DECISIONS.md)
CREATE INDEX ix_outbox_status_created_at ON outbox (status, created_at);
```

### user_points

```sql
CREATE TABLE user_points (
    user_id     BIGINT  PRIMARY KEY,
    balance     BIGINT  NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## 7. Redis Key 설계

| 용도 | Key 패턴 | 타입 | TTL | 근거 |
|------|----------|------|-----|------|
| 재고 카운터 | `stock:accommodation:{productId}` | Integer | 없음 (영속) | ADR-008 |
| 재고 Hold | `hold:user:{userId}:product:{productId}` | String ("HOLD" / "PG_PENDING") | **300초 (5분)** | ADR-003, ADR-008 |
| Rate Limit 버킷 | `rate:user:{userId}` | Hash (tokens, last_refill) | **60초** | ADR-005 |
| 멱등성 키 | `idempotency:{idempotencyKey}` | String ("PROCESSING" / "COMPLETED:{responseJson}") | **900초 (15분)** | ADR-006 |

**Rate Limit 버킷 상세** (ADR-005):
- `tokens`: 현재 토큰 수 (최대 5)
- `last_refill`: 마지막 보충 시각 (ms timestamp)
- 보충 속도: 초당 3 토큰, burst 최대 5 토큰

**Hold TTL 분기** (ADR-003, ADR-008):
- 5분 만료 시: 상태 확인 후 HOLD이면 즉시 `stock:accommodation:{productId}` INCR
- 5분 만료 시: 상태 PG_PENDING이면 60초 연장 후 재확인
- 연장 후에도 PG_PENDING이면: 강제 INCR + PG 취소 API (Saga 보상)

---

## 8. 재검토 트리거

구현에 직접 영향을 주는 재검토 트리거만 발췌:

| 트리거 | 영향 ADR | 재검토 내용 |
|--------|---------|-------------|
| 평시 TPS가 1000 이상으로 증가 | ADR-010 | Outbox 폴링 부하 → 메시지 브로커 도입 검토 |
| 컨슈머 수가 3개 이상으로 증가 | ADR-010 | in-process → Kafka/RabbitMQ 전환 검토 |
| MSA 분리 진행 시 | ADR-010 | EventPublisher 구현체를 메시지 브로커로 교체 |
| Redis Cluster 전환 시 | ADR-002 | Lua Script 키가 동일 슬롯에 있어야 함 — 키 설계 재검토 |
| 정상 사용자 Rate Limit trigger 비율이 1% 이상 | ADR-005 | 임계값 완화 (초당 3 토큰, burst 5 재검토) |
| 봇 폴링이 재고의 50% 이상 점유 패턴 관찰 시 | ADR-008 | CAPTCHA 또는 IP 기반 보조 Rate Limit 도입 검토 |
| 결제 수단 5개 초과 시 | ADR-009 | 두 계층 분리만으로 추상화 부족 → Plugin 아키텍처 검토 |
| PG 보상 호출 실패가 빈번히 발생 시 | ADR-009 | Two-Phase Commit 재평가 또는 PG 상태 조회 자동화 |
| Outbox 테이블 성능 저하 관찰 시 | ADR-010 | archive 정책 강화 또는 CDC(Debezium) 전환 검토 |
| Circuit Breaker Flapping이 빈번 시 | ADR-007 | `waitDurationInOpenState` 또는 `minimumNumberOfCalls` 조정 |
| 무통장 입금 등 비동기 결제 수단 추가 시 | ADR-003 | TTL 정책 분리 필요 |

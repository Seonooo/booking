# Feature 001: POST /booking 멱등성 처리 (ADR-006)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| In-Progress | TBD | 2026-05-03 | 2026-05-04 |

> **본 feature는 컨벤션 시연용으로 미리 작성됨**. 실제 구현 시 `tdd-planner`가 Status를 Planning으로 전이하고 Progress Log를 시작.

> **본 feature 파일은 self-contained로 작성/유지된다.** 모든 컨텍스트(적용 ADR 인용·ERD 엔티티·영향 코드 경로·테스트 패턴 번호)는 파일 내에 명시. 다른 세션이 본 파일만 보고 Phase 실행 가능해야 한다 — *"이전 대화에서 논의한 대로"* 같은 외부 대화 참조 금지.

## Request

> ADR-006 멱등성 처리를 `POST /booking`에 구현한다. 클라이언트가 발급한 UUID v4 멱등성 키를 받아 Redis(15분 TTL) + DB unique constraint 이중 계층으로 중복 결제를 차단한다. 동시 동일 키 요청은 정확히 1건만 통과해야 한다.

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태이고
  And   상품 42가 존재하고 재고가 10이며
  And   Redis와 MySQL이 정상 동작 중이고
  And   유효한 멱등성 키 "550e8400-e29b-41d4-a716-446655440000"가 발급되어 있다

Scenario: [happy] 신규 멱등성 키 → 200 OK + booking 생성
  Given 멱등성 키 "550e8400-..."가 Redis와 DB 어디에도 존재하지 않고
  When  사용자가 멱등성 키와 함께 POST /booking을 호출하면
  Then  HTTP 200 응답을 받고
  And   응답 본문에 booking_id가 포함되며
  And   Redis 키 상태는 COMPLETED로 갱신되고
  And   DB idempotency_key 테이블에 row가 생성된다

Scenario: [happy] 같은 키 + 같은 body, 처리 중 → 409 Conflict
  Given 멱등성 키 "550e8400-..."가 Redis에 PROCESSING 상태로 존재하고
  And   저장된 body_hash가 현재 요청의 body_hash와 일치하며
  When  사용자가 같은 멱등성 키와 같은 body로 다시 POST /booking을 호출하면
  Then  HTTP 409 응답을 받고
  And   응답 본문에 "처리 중, 잠시 후 재시도" 메시지가 포함되며
  And   새 booking은 생성되지 않는다

Scenario: [happy] 같은 키 + 같은 body, 이미 완료 → 200 OK + 캐시 응답
  Given 멱등성 키 "550e8400-..."가 Redis에 COMPLETED 상태로 존재하고
  And   response_payload가 캐시되어 있으며
  And   저장된 body_hash가 현재 요청의 body_hash와 일치하면
  When  사용자가 같은 멱등성 키와 같은 body로 다시 POST /booking을 호출하면
  Then  HTTP 200 응답을 받고
  And   응답 본문은 캐시된 response_payload와 정확히 일치하며
  And   새 booking은 생성되지 않는다

Scenario: [edge:tampering] 같은 키 + 다른 body → 422 Unprocessable Entity
  Given 멱등성 키 "550e8400-..."가 Redis에 어떤 상태로든 존재하고
  And   저장된 body_hash가 현재 요청의 body_hash와 다르면
  When  사용자가 같은 멱등성 키이지만 다른 body로 POST /booking을 호출하면
  Then  HTTP 422 응답을 받고
  And   응답 본문에 "데이터 충돌, 키 재발급" 메시지가 포함되며
  And   새 booking은 생성되지 않는다

Scenario: [edge:concurrency] 동시 동일 키 100건 → 1건만 성공, 99건 409
  Given 멱등성 키 "550e8400-..."가 Redis와 DB 어디에도 존재하지 않고
  When  100 클라이언트가 같은 멱등성 키와 같은 body로 동시에 POST /booking을 호출하면
  Then  정확히 1건은 HTTP 200을 받고
  And   정확히 99건은 HTTP 409를 받으며
  And   DB booking 테이블에는 1건만 INSERT되고
  And   재고는 정확히 1만큼만 DECR된다

Scenario: [edge:expiry] TTL 15분 만료 후 같은 키 재시도 → 200 OK (새 결제로 처리)
  Given 멱등성 키 "550e8400-..."가 16분 전에 발급되었고
  And   Redis에서 TTL 만료로 제거되었으며
  And   DB idempotency_key의 expires_at도 16분 전이 지났을 때
  When  사용자가 같은 멱등성 키로 POST /booking을 호출하면
  Then  HTTP 200 응답을 받고
  And   새로운 booking이 생성된다

Scenario: [edge:failure] Redis 장애 + 같은 키 재시도 → DB unique constraint가 차단
  Given 멱등성 키 "550e8400-..."가 DB idempotency_key 테이블에 COMPLETED로 존재하고
  And   Redis가 장애로 응답 불가 상태이며 (Circuit Breaker OPEN)
  When  사용자가 같은 멱등성 키로 POST /booking을 호출하면
  Then  ADR-007 Fail-Closed 정책에 따라 HTTP 503 응답을 받거나
  And   Redis 복구 후 재시도 시 DB unique constraint가 충돌을 감지해 409를 반환하며
  And   이중 booking은 절대 발생하지 않는다
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | 신규 멱등성 키 → 200 OK + booking 생성 | happy | `should_create_booking_and_return_200_when_key_is_new` | `BookingIdempotencyIntegrationTest.java` | red |
| 2 | 같은 키 + 같은 body, 처리 중 → 409 | happy | `should_return_409_when_same_key_in_processing_with_same_body` | `BookingIdempotencyIntegrationTest.java` | red |
| 3 | 같은 키 + 같은 body, 이미 완료 → 200 + 캐시 | happy | `should_return_cached_response_with_200_when_completed_with_same_body` | `BookingIdempotencyIntegrationTest.java` | red |
| 4 | 같은 키 + 다른 body → 422 | edge:tampering | `should_return_422_when_body_hash_differs` | `BookingIdempotencyIntegrationTest.java` | red |
| 5 | 동시 동일 키 100건 → 1건만 성공, 99건 409 | edge:concurrency | `should_block_concurrent_same_key_requests` | `BookingIdempotencyConcurrencyTest.java` | red |
| 6 | TTL 15분 만료 후 같은 키 재시도 → 200 | edge:expiry | `should_create_new_booking_when_key_expired_after_15_minutes` | `BookingIdempotencyIntegrationTest.java` | red |
| 7 | Redis 장애 + DB unique 차단 | edge:failure | `should_block_duplicate_via_db_unique_when_redis_unavailable` | `BookingIdempotencyIntegrationTest.java` | red |

**Edge case coverage**: 4/7 (57%) — `[edge:tampering]` 1, `[edge:concurrency]` 1, `[edge:expiry]` 1, `[edge:failure]` 1. ADR-013 §Edge Case 의무 조항 충족.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied ADRs**: ADR-006 (멱등성), ADR-002 (Lua atomic — Redis SETNX), ADR-007 (Redis 장애)
- **Test-first 의무 영역**: YES — 멱등성은 ADR-013 §의무 영역(application layer 멱등성 처리)에 명시
- **영향 엔티티 (`docs/ERD.md`)**: `idempotency_key`, `booking`
- **기존 패턴 참조**: ADR-006 §흐름 (Redis 1차 + DB 2차 이중 계층, body SHA256 검증)

### Phase 1: Architectural Blueprint

- [x] done
- **위임**: `code-architect`
- **검증 커맨드**:
  ```bash
  git diff --name-only                                    # 코드 변경 0건 (blueprint 문서만)
  ```
- **AC**: blueprint의 file path가 `docs/ARCHITECTURE.md` 디렉토리 구조에 정합. ERD `idempotency_key` 테이블 컬럼과 도메인 모델 일치.
- **결과**:

---

#### 1. 데이터 모델 sketch

##### `IdempotencyKey` — domain aggregate
`com.booking.domain.idempotency.IdempotencyKey`

ERD §4.6 `idempotency_key` 컬럼과 1:1 매핑. 외부 기술(JPA/Spring) 의존 0.

| 필드 | 타입 | 비고 |
|---|---|---|
| `idempotencyKey` | `UUID` | PK — 클라이언트 발급 UUID v4 |
| `userId` | `long` | 발행 주체 |
| `bodyHash` | `String` | CHAR(64) — SHA-256 hex |
| `status` | `IdempotencyStatus` | `PROCESSING` / `COMPLETED` |
| `responsePayload` | `String` | JSON (nullable — COMPLETED 이후 set) |
| `bookingId` | `Long` | nullable FK → `booking.id` |
| `createdAt` | `Instant` | |
| `expiresAt` | `Instant` | createdAt + 15분 (ADR-006) |

`IdempotencyStatus` enum (`com.booking.domain.idempotency.IdempotencyStatus`): `PROCESSING`, `COMPLETED`

도메인 불변식 메소드 (ADR-014 — domain에 외부 기술 import 금지):
- `isExpired(Instant now)`: boolean
- `isBodyHashMatching(String incomingHash)`: boolean
- `isProcessing()`: boolean
- `isCompleted()`: boolean
- `complete(String responsePayload)`: IdempotencyKey — PROCESSING → COMPLETED, 새 인스턴스 반환

##### `IdempotencyKeyJpaEntity` — JPA 매핑 (infrastructure)
`com.booking.infrastructure.persistence.IdempotencyKeyJpaEntity`

ERD §4.6 DDL과 동일 컬럼. `@Entity`, `@Table(name = "idempotency_key")`. domain `IdempotencyKey`와 상호 변환 메소드(`toDomain()`, `fromDomain()`).

##### Redis key schema (ADR-006 §흐름, ARCHITECTURE.md §7)
| 항목 | 값 |
|---|---|
| Key pattern | `idempotency:{idempotencyKey}` (UUID 문자열) |
| Value format | `PROCESSING:{bodyHash}` 또는 `COMPLETED:{bodyHash}:{responseJson}` |
| TTL | 900초 (15분) — ADR-006 |

---

#### 2. 인터페이스 sketch

##### `IdempotencyKeyService` — application layer
`com.booking.application.IdempotencyKeyService`

CONVENTIONS-CODE.md §2: driving port 구현 또는 use case service. ARCHITECTURE.md §3 `application/` 위치.

```java
public class IdempotencyKeyService {
    // Redis 1차 조회+SETNX (Lua atomic, ADR-002) → 3-state 분기
    public IdempotencyCheckResult checkAndReserve(UUID idempotencyKey, String bodyHash);

    // 결제 완료 후 호출 — Redis COMPLETED 갱신 + DB UPDATE
    // @Transactional 경계 안에서 DB 갱신, 트랜잭션 후 Redis 갱신
    public void complete(UUID idempotencyKey, String bodyHash, String responsePayload, long bookingId);

    // Redis 장애 Fail-Closed fallback (ADR-007) — Phase 1 시그니처 sketch
    private IdempotencyCheckResult redisFallback(UUID key, String hash, Exception e);
}
```

`IdempotencyCheckResult` (record, `com.booking.application`):

```java
public record IdempotencyCheckResult(
    ResultType type,         // NEW / PROCESSING / COMPLETED / HASH_MISMATCH
    String cachedResponse    // COMPLETED 시에만 non-null
) {
    public enum ResultType { NEW, PROCESSING, COMPLETED, HASH_MISMATCH }
}
```

HTTP 응답 매핑 (CONVENTIONS-CODE.md §3):
| ResultType | HTTP |
|---|---|
| NEW | 처리 계속 진행 |
| PROCESSING | 409 Conflict |
| COMPLETED | 200 OK + cachedResponse |
| HASH_MISMATCH | 422 Unprocessable Entity |

##### `IdempotencyKeyRepository` — domain driven port
`com.booking.domain.idempotency.IdempotencyKeyRepository`

CONVENTIONS-CODE.md §2: `<Aggregate>Repository` 인터페이스, `domain/<aggregate>/` 위치. 외부 기술 import 0 (ADR-014).

```java
public interface IdempotencyKeyRepository {
    Optional<IdempotencyKey> findById(UUID idempotencyKey);
    void save(IdempotencyKey idempotencyKey);
    void updateToCompleted(UUID idempotencyKey, String responsePayload, long bookingId);
}
```

##### `IdempotencyKeyJpaRepository` — infrastructure driven adapter
`com.booking.infrastructure.persistence.IdempotencyKeyJpaRepository`

CONVENTIONS-CODE.md §2: `<Aggregate>JpaRepository`, `infrastructure/persistence/` 위치. `IdempotencyKeyRepository` 인터페이스 implement. JPA/Spring 의존은 이 계층에만.

DB UNIQUE constraint (idempotency_key PK) 위반 시 `DataIntegrityViolationException` → application layer에서 409로 변환 (Redis 장애 시 DB 2차 방어선, ADR-006).

##### `IdempotencyLuaScript` — infrastructure Redis adapter
`com.booking.infrastructure.redis.IdempotencyLuaScript`

ARCHITECTURE.md §3 `infrastructure/redis/` 위치. ADR-002 Lua atomic 패턴.

```java
@Component
public class IdempotencyLuaScript {
    // KEYS[1] = idempotency:{key}, ARGV[1] = bodyHash, ARGV[2] = ttlSeconds
    @CircuitBreaker(name = "redisOps", fallbackMethod = "redisFallback")
    @Bulkhead(name = "redisOps")
    public IdempotencyCheckResult execute(UUID key, String bodyHash);

    private IdempotencyCheckResult redisFallback(UUID key, String bodyHash, Exception e);
    // throws ServiceUnavailableException → 503 (ADR-007 Fail-Closed)

    @CircuitBreaker(name = "redisOps", fallbackMethod = "completeRedisFallback")
    public void markCompleted(UUID key, String bodyHash, String responsePayload);
}
```

---

#### 3. Redis Lua script pseudo (ADR-002 atomic 패턴)

실제 Lua 코드는 Phase 3.3에서 작성. 본 문서는 의사 코드로 흐름만 명세.

파일 위치 (Phase 3.3): `src/main/resources/lua/idempotency_check.lua`

```
-- KEYS[1] = "idempotency:{uuid}"
-- ARGV[1] = incomingBodyHash
-- ARGV[2] = ttlSeconds (900)
-- Returns: ["NEW"] / ["PROCESSING", storedHash] / ["COMPLETED", storedHash, responseJson] / ["HASH_MISMATCH", storedHash]

local val = redis.call('GET', KEYS[1])

if val == false then
    -- 키 없음: SETNX (PROCESSING 상태로 저장)
    local stored = "PROCESSING:" .. ARGV[1]
    redis.call('SET', KEYS[1], stored, 'EX', tonumber(ARGV[2]), 'NX')
    -- NX 성공 → NEW 반환, NX 실패(동시 경쟁) → 재조회 후 PROCESSING 반환
    return {"NEW"}
end

-- 키 있음: storedStatus, storedHash 파싱 (첫 번째 ":" 구분자)
if storedHash ~= ARGV[1] then
    return {"HASH_MISMATCH", storedHash}
end
if storedStatus == "PROCESSING" then
    return {"PROCESSING", storedHash}
end
-- storedStatus == "COMPLETED"
return {"COMPLETED", storedHash, responseJson}
```

ADR-002 준수 포인트: GET + 상태비교 + SETNX 가 하나의 Lua 스크립트 안에서 실행 — Redis 단일 스레드 보장으로 race condition 0.

---

#### 4. `BookingController.create()` 흐름 시퀀스

ARCHITECTURE.md §5 요청 흐름 + CLAUDE.md CRITICAL #1 (PG 호출은 DB 트랜잭션 밖) 반영.

메소드 시그니처:
```java
BookingController.create(
    @RequestHeader("Idempotency-Key") String rawKey,
    @Valid @RequestBody CreateBookingRequest request
)
```

단계별 흐름:

```
단계 1  [멱등성 체크 — @Transactional 밖]
        bodyHash = BodyHashCalculator.calculate(request)
        result   = IdempotencyKeyService.checkAndReserve(key, bodyHash)
        ├─ COMPLETED     → 200 OK (result.cachedResponse 반환, 종료)
        ├─ PROCESSING    → 409 Conflict (종료)
        └─ HASH_MISMATCH → 422 Unprocessable Entity (종료)
        Redis 장애       → 503 (ADR-007 Fail-Closed)

단계 2  [Rate Limit — @Transactional 밖]
        RateLimitService.check(userId) → 429 / 503

단계 3  [재고 DECR — @Transactional 밖]
        StockService.decrement(productId, userId) → SOLD_OUT 200 / 503

단계 4  [PaymentComposition 생성+검증 — @Transactional 밖]
        new PaymentComposition(methods)
        → InvalidPaymentCompositionException → 400

단계 5  [PG 호출 — @Transactional 밖, CRITICAL #1 (ADR-009)]
        ExternalPaymentMethod.execute(paymentRequest)
        → PG 거절 → StockService.increment() 후 400
        → PG Timeout → UNKNOWN 처리, 200 PENDING_CONFIRMATION

단계 6  [@Transactional BEGIN]
        InternalPaymentMethod.execute()           — 포인트 차감 (있는 경우)
        bookingRepository.save(booking)           — status: COMPLETED
        idempotencyKeyRepository.save(ik)         — DB 2차 방어선 (ADR-006)
        outboxRepository.save(outboxEvent)
        └─ Outbox INSERT 실패 → rollback X, fallback log [OUTBOX_INSERT_FAILED] (ADR-010)
        [@Transactional COMMIT]

단계 7  [Redis COMPLETED 갱신 — @Transactional 후]
        IdempotencyLuaScript.markCompleted(key, bodyHash, responsePayload)
        Redis 장애 → DB 2차 방어선이 저장됨이므로 warning log (ADR-006)

단계 8  [200 OK 응답]
```

트랜잭션 경계 요약:
- `@Transactional` 범위: 단계 6만 (InternalPayment + booking + idempotency_key + outbox INSERT)
- PG 호출(단계 5): 트랜잭션 밖 — CRITICAL #1 (ADR-009)
- 멱등성 Redis 조회(단계 1): 트랜잭션 밖 — 처리 전 검증 (ADR-006 §흐름)

---

#### 5. Body hash 알고리즘

ADR-006 §핵심 필드 정의 기준. helper 위치: `com.booking.application.BodyHashCalculator`

입력 필드 (결과에 영향을 주는 모든 차감 자원):
| 필드 | 타입 | 비고 |
|---|---|---|
| `userId` | `long` | 결제 주체 |
| `productId` | `long` | 결제 대상 (`accommodationId`) |
| `amount` | `BigDecimal` | 결제 총액 — `toPlainString()` (지수 표기 방지) |
| `paymentMethod` | `String` | `CARD` / `YPAY` / `POINT` |
| `points` | `long` | 사용 포인트 (미사용 시 0) |

알고리즘:
```
input  = String.valueOf(userId)
       + "|" + String.valueOf(productId)
       + "|" + amount.toPlainString()
       + "|" + paymentMethod.toUpperCase()
       + "|" + String.valueOf(points)

bytes  = input.getBytes(StandardCharsets.UTF_8)
digest = MessageDigest.getInstance("SHA-256").digest(bytes)
hex    = HexFormat.of().formatHex(digest)   // Java 17+, lowercase, 64자
```

시그니처: `BodyHashCalculator.calculate(CreateBookingRequest request): String`

구분자 `|` 선택 이유: 필드 간 ambiguity 제거 (예: userId=1, productId=23 vs userId=12, productId=3 → 다른 hash 보장).

---

#### 6. ADR Cross-reference 표

| Blueprint 결정 | 근거 ADR / ERD |
|---|---|
| Redis 1차 + DB UNIQUE 2차 이중 계층 | ADR-006 §축 2 옵션 C |
| Lua script atomic (GET + 비교 + SETNX 단일 스크립트) | ADR-002, ADR-006 §흐름 |
| 3-state 응답 (409 PROCESSING / 200 COMPLETED / 422 HASH_MISMATCH) | ADR-006 §축 4, ERD §4.6 §3-state 응답 분기 |
| TTL 900초 (15분) | ADR-006 §축 3 옵션 B |
| Redis 장애 → 503 Fail-Closed | ADR-007 §축 4, CLAUDE.md §SLO Priority |
| `IdempotencyKeyRepository` interface → `domain/idempotency/` | ADR-014, CONVENTIONS-CODE.md §2 |
| `IdempotencyKeyJpaRepository` → `infrastructure/persistence/` | ADR-014, CONVENTIONS-CODE.md §2 |
| `IdempotencyLuaScript` → `infrastructure/redis/` | ARCHITECTURE.md §3 |
| domain layer 외부 기술 의존 0 | ADR-014 §domain 외부 기술 의존성 0 원칙 |
| PG 호출은 `@Transactional` 밖 (단계 5) | CLAUDE.md CRITICAL #1, ADR-009 §Saga |
| `body_hash CHAR(64)`, `status VARCHAR(20)`, `expires_at TIMESTAMP` | ERD §4.6, CONVENTIONS-CODE.md §7 |
| `idempotency:{uuid}` Redis key pattern | ARCHITECTURE.md §7 |
| SHA-256(userId|productId|amount|paymentMethod|points) | ADR-006 §핵심 필드 정의 |

정합 미스 없음. ADR/ERD/CONVENTIONS와 모든 결정이 정합됨.

### Phase 2: RED — Failing Tests

- [x] done
- **위임**: `test-author`
- **테스트 파일**:
  - `src/test/java/com/booking/idempotency/IdempotencyKeyServiceTest.java` (Unit — body_hash 계산, 3-state 분기 로직)
  - `src/test/java/com/booking/integration/BookingIdempotencyIntegrationTest.java` (Integration — 7 Scenario 모두)
  - `src/test/java/com/booking/concurrency/BookingIdempotencyConcurrencyTest.java` (Concurrency — Scenario 5: 100 동시 요청)
- **시나리오 ↔ 테스트 메소드 매핑**:
  - "신규 멱등성 키 → 200 OK + booking 생성" → `should_create_booking_and_return_200_when_key_is_new`
  - "같은 키 + 같은 body, 처리 중 → 409" → `should_return_409_when_same_key_in_processing_with_same_body`
  - "같은 키 + 같은 body, 이미 완료 → 200 + 캐시" → `should_return_cached_response_with_200_when_completed_with_same_body`
  - "같은 키 + 다른 body → 422" → `should_return_422_when_body_hash_differs`
  - "동시 동일 키 100건 → 1건 성공, 99건 409" → `should_block_concurrent_same_key_requests` (Concurrency)
  - "TTL 15분 만료 후 → 새 결제" → `should_create_new_booking_when_key_expired_after_15_minutes`
  - "Redis 장애 + DB unique → 차단" → `should_block_duplicate_via_db_unique_when_redis_unavailable`
- **mocking 경계**:
  - PG: WireMock (Phase 1 blueprint에서 PG 호출 포함될 시)
  - Redis: Testcontainers Redis 7
  - MySQL: Testcontainers MySQL 8.0+
  - 내부 의존성: Mockito (Unit 테스트만)
- **참고 패턴**: Pattern 5 (3-state 응답 + 동시 동일 키), Pattern 3 (Redis Lua atomic 응용)
- **검증 커맨드** (모두 실패해야 RED 통과):
  ```bash
  ./gradlew test --tests IdempotencyKeyServiceTest                       # Unit
  ./gradlew test --tests BookingIdempotencyIntegrationTest               # Integration (Scenario 1~4, 6, 7)
  ./gradlew test --tests BookingIdempotencyConcurrencyTest               # Concurrency (Scenario 5)
  # 단일 시나리오만:
  ./gradlew test --tests "BookingIdempotencyIntegrationTest.should_return_422_when_body_hash_differs"
  ```
- **AC**: 위 7 시나리오 테스트 모두 fail (production 미구현). 컴파일 성공.
- **결과**:
  - **테스트 파일 (5)**: `IntegrationTestSupport` (Pattern 1 base, MySQL 8.0 + Redis 7-alpine Testcontainers), `BookingTestDataBuilder` (test data factory 3종), `IdempotencyKeyServiceTest` (Unit 9 메소드 — `BodyHashCalculator` 4 + `IdempotencyKeyService` 5), `BookingIdempotencyIntegrationTest` (Scenario 1~4, 6, 7 — 6 메소드), `BookingIdempotencyConcurrencyTest` (Scenario 5 — 100 동시 요청 1 메소드). 총 16 테스트 메소드.
  - **Stub 8 (Phase 3 GREEN에서 본격 구현)**: `IdempotencyKey` / `IdempotencyStatus` / `IdempotencyKeyRepository` (domain) / `IdempotencyKeyService` / `BodyHashCalculator` / `IdempotencyCheckResult` (application) / `BookingController` / `CreateBookingRequest` (api). 메소드 body는 `throw new UnsupportedOperationException()`.
  - **검증 결과**: `./gradlew compileTestJava` BUILD SUCCESSFUL. `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyServiceTest"` → 9 tests, 9 failed (UnsupportedOperationException) — RED phase 정합. Integration / Concurrency는 Testcontainers Docker 필요해 본 phase verify에서 skip — Phase 3 진입 시 docker compose up 후 재검증.
  - **`@Tag` 매핑**: happy → `@Tag("happy")` / edge:tampering / edge:concurrency / edge:expiry / edge:failure → 각각 `@Tag("edge")` + `@Tag("edge:CATEGORY")` 이중 적용 (test-author.md Gherkin Mapping Rules §3 정합).
  - **위치 정합**: 모든 테스트 위치 `CONVENTIONS-FILE.md` §5 (`<aggregate>` / `integration/` / `concurrency/` / `testsupport/`). production stub 위치 `CONVENTIONS-FILE.md` §4 + ARCHITECTURE.md §3 정합 (`api/booking/` aggregate sub-package, `domain/idempotency/` Phase 1 blueprint + PR-A로 §3 등록 예정).

### Phase 3: GREEN — Minimal Implementation

5 레이어 변경 → **sub-phase 의무 분할** (≥3 레이어 임계, ADR-013 §Scope 최소화).
의존 순서: Domain → Application → Infrastructure → API.

#### Phase 3.1: Domain layer GREEN

- [x] done
- **작성 대상**:
  - `IdempotencyKey` Aggregate (status enum, body_hash 검증 메소드)
  - `IdempotencyKeyRepository` port (interface only)
- **CRITICAL 제약**:
  - Domain 불변식은 생성자에서 강제 (ADR-009 Domain VO 패턴)
  - Repository는 port (interface)만, 구현은 Phase 3.3
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests IdempotencyKeyTest
  ```
- **AC**: `IdempotencyKeyTest` GREEN. Service/Integration 테스트는 RED 유지 (다른 레이어 미구현).
- **결과**:
  - `IdempotencyKey.java` GREEN — 모든 필드 final, 생성자 NPE 검증 (5 필드 — idempotencyKey/bodyHash/status/createdAt/expiresAt), `complete(String, long)` 가 새 인스턴스 반환 (immutable aggregate). Phase 1 blueprint §1 데이터 모델 정합 (ERD §4.6 8 필드 매핑).
  - `IdempotencyStatus`, `IdempotencyKeyRepository` (port) — stub 구조 그대로 유지 (이미 Phase 1 blueprint 정합).
  - **`IdempotencyKeyTest.java` 신규** — Phase 2 RED 단계에서 누락됐던 도메인 unit test. 5 nested groups (Constructor / Status / BodyHash / Expiration / Complete) × 14 메소드. AssertJ + JUnit 5 nested.
  - **검증**: `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyTest"` BUILD SUCCESSFUL (14/14 GREEN). `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyServiceTest"` BUILD FAILED (9/9 fail — Service 미구현, RED 유지) — AC 정합.

#### Phase 3.2: Application layer GREEN

- [x] done
- **작성 대상**:
  - `IdempotencyKeyService` (3-state 응답 분기 로직: PROCESSING → 409 / COMPLETED+match → 200 cache / 미스매치 → 422)
  - `BodyHashCalculator` helper (SHA256(userId + productId + amount + paymentMethod + points))
- **CRITICAL 제약**:
  - 멱등성 검증은 트랜잭션 시작 전에 (ADR-006 흐름)
  - Repository는 Mockito mock으로 격리 (Infrastructure 미구현 상태)
  - PG 호출은 트랜잭션 밖 (CLAUDE.md CRITICAL #1)
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests IdempotencyKeyServiceTest
  ```
- **AC**: `IdempotencyKeyServiceTest` GREEN. Integration 테스트는 여전히 RED.
- **결과**:
  - `IdempotencyKeyService.checkAndReserve(UUID, String)` — Repository 단일 의존 (LuaScript 통합은 Phase 3.3 영역). 분기 우선순위: empty → NEW / expired → NEW / `!isBodyHashMatching` → HASH_MISMATCH / PROCESSING → PROCESSING / COMPLETED → COMPLETED+cachedResponse.
  - `IdempotencyKeyService.complete(UUID, String, String, long)` — `idempotencyKeyRepository.updateToCompleted()` 위임. `bodyHash` 인자는 Phase 3.3 Redis 갱신 시 storedHash 검증용으로 보존 (현재 unused).
  - `BodyHashCalculator.calculate(CreateBookingRequest)` — `userId|productId|amount.toPlainString()|paymentMethod.toUpperCase()|points` SHA-256 hex 64자. `HexFormat.of()` (Java 17+). `|` 구분자로 인접 정수 필드 ambiguity 차단.
  - **검증**: `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyServiceTest" --tests "com.booking.idempotency.IdempotencyKeyTest"` BUILD SUCCESSFUL — domain 14 + service/calculator 9 = 23/23 GREEN. Integration / Concurrency는 여전히 RED (Phase 3.3 LuaScript / Phase 3.4 Controller 미구현) — AC 정합.

#### Phase 3.3: Infrastructure layer GREEN

- [x] done
- **작성 대상**:
  - Redis Lua script (`idempotency_setnx.lua` — SETNX + body_hash 비교, atomic)
  - `IdempotencyKeyRedisAdapter` (Lua 호출)
  - `IdempotencyKeyJpaRepository` JPA adapter (DB 영속 계층)
- **CRITICAL 제약**:
  - Redis SETNX + body_hash 비교는 Lua atomic 강제 (ADR-002)
  - DB unique constraint는 `(idempotency_key)` UNIQUE 인덱스 활용 (ERD §4.6)
  - Redis 장애 시 Fail-Closed (ADR-007) — Circuit Breaker OPEN 시 503
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests IdempotencyLuaScriptTest        # Slice + Testcontainers Redis
  ./gradlew test --tests IdempotencyKeyJpaRepositoryTest # Slice + Testcontainers MySQL
  ```
- **AC**: 두 slice 테스트 GREEN. Lua atomic 동작 + DB UNIQUE 충돌 시 적절한 예외.
- **결과**:
  - **Flyway baseline**: `V1__init.sql` 9 테이블 (users / accommodation / booking / payment_attempt / cancellation_intent / outbox_event / processed_event / idempotency_key / shedlock) — ERD §8 그대로. CONVENTIONS-FILE.md §6 정합 (`utf8mb4_0900_ai_ci`, 컬럼 → PK → UNIQUE → INDEX → FK 순서).
  - **JPA layer** (`infrastructure/persistence/`): `IdempotencyKeyJpaEntity` (8 필드 매핑, `@Enumerated(STRING)` status, `JSON` columnDefinition) + `IdempotencyKeyJpaRepository` (Spring Data + `@Modifying @Query updateToCompleted`) + `IdempotencyKeyRepositoryAdapter` (`@Component`, domain port impl, `@Transactional` save / update). domain↔entity 매핑 한 곳에 모음.
  - **Redis Lua** (`infrastructure/redis/`): `idempotency_setnx.lua` (GET + SETNX + body_hash 비교, atomic — ADR-002) + `IdempotencyLuaScript` (`@Component` + `DefaultRedisScript` + `@CircuitBreaker(name="redisOps") @Bulkhead("redisOps")`) + `RedisUnavailableException` (Resilience4j fallback).
  - **Resilience4j config**: `application.yml` 에 `redisOps` circuit breaker + bulkhead — ADR-007 §Decision 임계값 (5초 TIME_BASED / failureRate 50% / slowCallDuration 1s / waitDurationInOpenState 5s / maxConcurrent 100).
  - **Service-Lua 통합**: `IdempotencyKeyService` 생성자 `(luaScript, repository)` 변경. `checkAndReserve` Lua 1차 호출 → `RedisUnavailableException` catch → `checkInDatabase()` DB 2차 fallback. 둘 다 같은 5-step 분기 (empty / expired / hash mismatch / processing / completed).
  - **`application-test.yml` 정정** (test-author 발견 결함): `ddl-auto: validate` → `none`. Hibernate 6 가 `body_hash CHAR(64)` 를 `VARCHAR(64)` 로 매칭 실패해 모든 test profile context 부팅 fail. Flyway 가 schema 단일 출처라 validate 비활성이 정합.
  - **검증** (모두 GREEN):
    - `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyServiceTest" --tests "com.booking.idempotency.IdempotencyKeyTest"` — unit 23 GREEN (Lua mock + DB fallback 양쪽)
    - `./gradlew test --tests "com.booking.idempotency.IdempotencyKeyJpaRepositoryTest"` — slice 6 GREEN (Testcontainers MySQL 8.0)
    - `./gradlew test --tests "com.booking.idempotency.IdempotencyLuaScriptTest"` — slice 6 GREEN (Testcontainers Redis 7-alpine, 100 동시 동일 키 atomic 검증 포함)

#### Phase 3.4: API + Integration GREEN

- [ ] pending
- **작성 대상**:
  - `BookingController.create()` (멱등성 키 검증 진입 흐름)
  - `BookingRequest` DTO + `@Valid` Bean Validation
  - `BookingExceptionHandler` (`InvalidPaymentCompositionException` → 422 등)
- **CRITICAL 제약**:
  - PG 호출은 DB 트랜잭션 밖 (CLAUDE.md CRITICAL #1)
  - `@Valid` 강제 — 미적용 시 java-reviewer Phase 5에서 차단
- **검증 커맨드** (Phase 2 RED의 7 시나리오 모두 GREEN 검증):
  ```bash
  ./gradlew test --tests BookingIdempotencyIntegrationTest
  ./gradlew test --tests BookingIdempotencyConcurrencyTest
  ./gradlew test                                        # 기존 테스트 미파괴 확인
  ```
- **AC**: Phase 2 RED의 7 시나리오 모두 GREEN. 기존 모든 테스트 미파괴.
- **결과**: ...

### Phase 4: REFACTOR

- [ ] pending
- **위임**: 호출자 (지엽적 — 명명·중복 정리)
- **검증 커맨드**:
  ```bash
  ./gradlew test                                                  # 전체 테스트 GREEN 유지
  ```
- **AC**: 모든 테스트 GREEN 유지. 본 phase에서 새 테스트 추가 금지 (refactor only).
- **결과**: ...

### Phase 5: Review

- [ ] pending
- **위임 (병렬)**:
  - `java-reviewer` — `@Transactional` 경계, Redis 호출의 Fail-Closed 처리, 멱등성 키 검증 시점 (ADR-006: "처리 전" 검증 강제)
  - `database-reviewer` — `idempotency_key` 테이블 인덱스, expires_at 기반 정리 배치 쿼리
- **검증 커맨드**:
  ```bash
  ./gradlew check                                                 # 전체 통합 (test + integration)
  git diff main...HEAD -- 'src/**/*.java'                         # agent 리뷰 대상
  ```
- **AC**: agent 리뷰에서 CRITICAL/HIGH 0건. ADR-006의 3-state 응답 분기·body_hash 검증·Fail-Closed 정책 모두 코드에 반영.
- **결과**: ...

### Phase 6: Concurrency / Load Verification

- [ ] pending
- **트리거**: ADR-006 (동시 동일 키), ADR-002 (Redis Lua atomic)
- **위임**: `test-author` (Pattern 5 — 100 동시 요청)
- **도구**: ExecutorService + CountDownLatch + Testcontainers Redis + MySQL
- **검증 대상 메트릭**:
  - 100 동시 동일 키 → 정확히 1건 200, 99건 409 (oversell·undersell 0건)
  - DB booking row 정확히 1건
  - 재고 정확히 1만큼 DECR
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests "com.booking.concurrency.*"              # 동시성 edge case 전체 (concurrency 패키지)
  ./gradlew test --tests BookingIdempotencyConcurrencyTest               # 본 feature만
  ```
- **AC**: `should_block_concurrent_same_key_requests` 통과 + 메트릭 충족 (200 정확히 1, 409 정확히 99, DB row 1, stock decrement 1).
- **결과**: ...

---

## Progress Log

(작성 시작 시 한 줄씩 append)

- 2026-05-03 — Feature file pre-written as convention demo. Status remains Draft until tdd-planner takes over.
- 2026-05-04 — Phase 1 (Architectural Blueprint) done by `code-architect`. 5 sub-sections (data model / interfaces / Lua pseudo / controller flow / body hash) inline. ADR cross-reference 표 14 항목 모두 정합. Status → In-Progress. Follow-up: ARCHITECTURE.md §3에 `domain/idempotency/` sub-package 등록 (별도 PR), 단계 5 PG Timeout 응답 코드 명확화 (Phase 2 시점).
- 2026-05-04 — Phase 2 (RED — Failing Tests) done by `test-author`. 5 test 파일 (16 메소드) + 8 production stub. `compileTestJava` BUILD SUCCESSFUL, unit test 9/9 fail (UnsupportedOperationException — RED 정합). Integration / Concurrency 검증은 Phase 3 진입 시 docker compose up 후. Scenario Map status: pending → red.
- 2026-05-04 — Phase 2 RED 완전 검증 (docker daemon + Testcontainers): Integration 6/6 fail (`AssertionFailedError` — stub 응답 mismatch), Concurrency 1/1 fail. Spring Boot context + MySQL 8.0 + Redis 7-alpine 모두 정상 부팅. Phase 2 RED AC 100% 충족 (16/16 fail + compile pass).
- 2026-05-04 — Phase 3.1 (Domain GREEN) done. `IdempotencyKey` 본격 구현 (immutable aggregate, complete()→ 새 인스턴스). Phase 2에서 누락됐던 `IdempotencyKeyTest` 보강 작성 (14 메소드 nested). `./gradlew test --tests IdempotencyKeyTest` BUILD SUCCESSFUL. `IdempotencyKeyServiceTest` 여전히 RED (Phase 3.2 영역) — AC 정합.
- 2026-05-04 — Phase 3.2 (Application GREEN) done. `IdempotencyKeyService` checkAndReserve 5-step 분기 (empty/expired→NEW, hash mismatch, PROCESSING, COMPLETED+cached) + complete delegate. `BodyHashCalculator` SHA-256 hex 64자 (HexFormat.of()) + `|` 구분자 ambiguity 차단. unit test 23/23 GREEN (domain 14 + service/calculator 9). Integration / Concurrency 여전히 RED (Phase 3.3+ 영역) — AC 정합.
- 2026-05-04 — Phase 3.3 (Infrastructure GREEN) done. Flyway V1 baseline 9 테이블 (ERD §8) + JPA adapter 3종 (Entity + JpaRepository + RepositoryAdapter, port impl) + Redis Lua (idempotency_setnx.lua + IdempotencyLuaScript + RedisUnavailableException) + Resilience4j config (redisOps CB + Bulkhead, ADR-007 §Decision). Service-Lua 통합 — checkAndReserve Lua 1차 → RedisUnavailableException catch → DB 2차 fallback. `application-test.yml` ddl-auto validate→none 정정 (Hibernate 6 + body_hash CHAR(64) 매핑 fail 회피, Flyway 단일 출처). 검증: unit 23 + slice 12 = 35 GREEN (`IdempotencyKeyServiceTest` + `IdempotencyKeyTest` + `IdempotencyKeyJpaRepositoryTest` + `IdempotencyLuaScriptTest`). Integration / Concurrency 여전히 부분 RED (Controller 미구현, Phase 3.4 영역).

---

## Outcome (feature Done 시 채움)

- **Files created/modified**: TBD
- **Tests added**: TBD
- **ADR validation**: ADR-006 매트릭스 row "body_hash 일치/불일치 / 3-state 응답 / 동시 동일 키"의 시나리오 7개 모두 테스트로 매핑 (계획)
- **Follow-up**: 멱등성 키 만료 정리 배치(`expires_at < NOW()`)는 별도 feature로 분리

# Feature 003: Stock Counter — Redis Lua atomic DECR + 사용자 단위 hold (ADR-008 amendment)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained 원칙 (ADR-013).** 모든 컨텍스트(REQUIREMENTS / ADR / ERD / 영향 코드 경로 / Pattern 번호)는 본 파일 안에서 인라인. 외부 대화 참조 금지.

## Request

> 사용자: *"내가 전달 준 API 구현을 최우선 목적으로 빠르게 작업해보자"* → REQUIREMENTS §1.2 의 POST /booking 풀 컨트랙트 진입 첫 step. 한정 수량 10개 + 선착순 = MVP 본질.
>
> 추가 요구 (2026-05-04 turn): *"booking api 의 경우 주문서 정보를 입력받아 결제를 진행하고 최종 주문을 생성하는 API"* — REQUIREMENTS §1.2 정의 정합. PG 결제 실패 시 다른 결제 수단으로 재시도 가능해야 하므로 *"즉시 INCR"* 모델은 부적합. ADR-008 amendment 트리거 → *"hold 유지 (5분 TTL) + TTL 만료 sweeper 가 INCR"* 로 정정.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `docs/REQUIREMENTS.md` §1.2 | POST /booking = 결제 진행 + 최종 주문 생성 1단위 |
| `docs/adr/ADR-008-stock-counter.md` §정정 사유 (2026-05-04) | 결제 실패 시 hold 유지 (TTL 만료 sweeper 가 INCR). 결제 시도자 UX 우선 |
| `docs/adr/DECISIONS.md` §11 케이스 1 (amendment 2026-05-04) | PG 거절 → hold 유지 + 400 + 다른 결제 수단 재시도 안내 |
| `docs/adr/ADR-002-redis-lua-atomic.md` | 재고 DECR / hold key SET 은 Lua atomic |
| `docs/adr/ADR-007-redis-fallback.md` | Redis 의존 컴포넌트 Fail-Closed → 503 |
| `docs/adr/ADR-014-hexagonal-architecture.md` | domain port + infrastructure adapter 분리 |

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태이고
  And   accommodation(id=42, name="테스트 숙소", base_price=50000)이 존재하며
  And   Redis stock:accommodation:42 = 10 으로 초기화돼 있다

Scenario: [happy] 재고 10 → 첫 진입 → DECR + booking COMPLETED → 200
  Given Redis stock:accommodation:42 == 10
  When  사용자가 POST /booking 을 호출하면 (CARD 50000)
  Then  HTTP 200 응답을 받고
  And   응답에 bookingId 가 포함되며
  And   Redis stock:accommodation:42 == 9 이다
  And   Redis hold:user:1001:product:42 키가 5분 TTL 로 존재한다
  And   booking row 1건 (status=COMPLETED) 이 생성됐다

Scenario: [edge:boundary] 재고 1 → 진입 성공 → DECR 0
  Given Redis stock:accommodation:42 == 1
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 200 응답을 받고
  And   Redis stock:accommodation:42 == 0 이다

Scenario: [edge:boundary] 재고 0 → SOLD_OUT → 409
  Given Redis stock:accommodation:42 == 0
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 409 응답을 받고
  And   응답 메시지에 "SOLD_OUT" 이 포함되며
  And   booking row 가 생성되지 않았고 (count == 0)
  And   PG 호출이 발생하지 않았다 (WireMock 호출 0회)
  And   idempotency_key 가 Redis 에서 삭제되어 클라이언트가 새 키로 재시도 가능하다

Scenario: [edge:concurrency] 재고 10 + 100 동시 요청 → 정확히 10 success, 90 SOLD_OUT
  Given Redis stock:accommodation:42 == 10
  And   100명의 서로 다른 사용자가 각자 고유 idempotency key 를 들고 동시에 POST /booking 을 호출할 때
  Then  정확히 10건이 HTTP 200 (booking COMPLETED) 이고
  And   정확히 90건이 HTTP 409 (SOLD_OUT) 이며
  And   Redis stock:accommodation:42 == 0 이고 (oversell 0건)
  And   booking row count == 10 이다 (DB UNIQUE constraint 와 함께 무결성 보장)
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | 재고 10 → 첫 진입 → 200 + hold key set | happy | `should_decrement_stock_and_set_hold_key_when_purchase_succeeds` | `BookingStockIntegrationTest.java` | GREEN |
| 2 | 재고 1 → 진입 성공 | edge:boundary | `should_decrement_stock_to_zero_when_last_one` | `BookingStockIntegrationTest.java` | GREEN |
| 3 | 재고 0 → SOLD_OUT 409 | edge:boundary | `should_return_409_sold_out_when_stock_is_zero` | `BookingStockIntegrationTest.java` | GREEN |
| 4 | 100 동시 요청 → 10 success / 90 SOLD_OUT, oversell 0 | edge:concurrency | `should_oversell_zero_when_100_concurrent_requests_for_stock_10` | `BookingStockConcurrencyTest.java` | GREEN |

**Edge case coverage**: 3/4 (75%) — `[edge:boundary]` ×2, `[edge:concurrency]` ×1. ADR-013 §Edge Case 의무 조항 충족 + ADR-002/008 *동시성 의무 영역* 충족.

> **PG 결제 실패 (4XX/5XX/timeout) 시나리오는 본 feature out-of-scope** — Saga+Outbox feature + Reconciliation feature 영역. 본 feature 는 *재고 hold + happy path 완성도* 만 다룬다 (자세한 분리 근거는 §Out-of-Scope).

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied**:
  - `REQUIREMENTS.md` §1.2 (POST /booking 정의)
  - `ADR-008` amendment 2026-05-04 (결제 실패 시 hold 유지)
  - `ADR-002` (Lua atomic), `ADR-007` (Fail-Closed), `ADR-014` (헥사고날)
  - `DECISIONS.md` §11 케이스 1 amendment 2026-05-04
- **Test-first 의무 영역**: **YES** — ADR-002 + ADR-008 모두 ADR-013 §의무 영역 매트릭스. 동시성 (Scenario 4) 가 핵심 정합성 시나리오.
- **영향 엔티티 (`docs/ERD.md`)**: 없음 — Stock 은 Redis-only (ERD §2.2 *out-of-scope* "Stock counter — Redis 데이터(테이블 아님)"). 본 PR 신규 RDB 테이블 추가 X.
- **현재 코드 상태**:
  - `BookingService.create()` 가 `idempotency check → PaymentComposition.executeExternal → DB persist (status COMPLETED 직접)` 구조. 재고 검증 없음.
  - `IdempotencyLuaScript` 가 ADR-002/006 패턴으로 `idempotency_setnx.lua` / `idempotency_complete.lua` 사용. **본 feature 의 Stock Lua adapter 가 동일 패턴 차용**.
  - `BookingStatus` enum 이 `HOLD / PG_PENDING / COMPLETED / FAILED / UNKNOWN` 가지지만 본 PR 은 여전히 `COMPLETED` 직접 — `HOLD/PG_PENDING` 상태 머신 본격 전이는 **out-of-scope** (Saga + Outbox feature 에서).

### Phase 1: Architectural Blueprint

#### 1. 도메인 — `domain/stock/`

| 파일 | 역할 | 비고 |
|---|---|---|
| `StockRepository` (port) | `boolean tryHold(long accommodationId, long userId, int ttlSeconds)` / `void init(long accommodationId, int initialCount)` (test fixture 용) | driven port. ADR-014. **release 메소드 본 PR 미포함** — TTL sweeper feature 진입 시점에 추가 |
| `StockSoldOutException` | RuntimeException — `tryHold` 실패 시 throw | application 레이어가 던지면 GlobalExceptionHandler 가 409 매핑 |

> **Stock 은 aggregate X**: 단순 카운터 + Redis-only. JPA entity / Booking aggregate 와 의 cross-aggregate update 없음. ADR-008 §데이터 모델 정합 (*"Redis Key: stock:accommodation:{id}, Type: Integer counter"*).
>
> **`release` 메소드를 본 PR 에 포함하지 않는 근거**: ADR-008 amendment 결정 *"결제 실패 시 hold 유지 / TTL 만료 시 sweeper 가 INCR"* 정합. 본 feature 의 happy path 흐름에서 release 호출 시점이 없음 (PG 성공 → hold 가 booking 으로 전환). release Lua script 추가는 sweeper feature 진입 시점에 추가.

#### 2. 인프라 — `infrastructure/redis/`

| 파일 | 역할 |
|---|---|
| `lua/stock_hold.lua` | ADR-008 §진입 로직 그대로. KEYS[1]=stock key, ARGV[1]=hold key, ARGV[2]=TTL. Returns `{1}`/`{0}` |
| `StockRedisAdapter implements StockRepository` | `@Component`. `IdempotencyLuaScript` 와 동일한 Resilience4j `@CircuitBreaker(name="redisOps") + @Bulkhead(name="redisOps")` 패턴. `tryHold` fallback → `RedisUnavailableException` (ADR-007 Fail-Closed) |

**Lua 스크립트 본문** (ADR-008 §진입 로직 그대로):

`stock_hold.lua`:
```lua
-- KEYS[1]: stock key (e.g., "stock:accommodation:42")
-- ARGV[1]: hold key (e.g., "hold:user:1001:product:42")
-- ARGV[2]: TTL seconds (300 = 5분)
-- Returns: {1} = entered, {0} = sold out or already held by same user (booking COMPLETED)

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return {0}
end

-- 멱등성: 같은 사용자가 이미 hold 중이면 재진입 불가
-- (ADR-008 amendment 후 의미: booking COMPLETED 사용자만 차단.
--  결제 실패 후 재시도는 같은 hold 재사용 — DECR 안 함, 호출자가 hold key EXISTS 시 별도 처리)
if redis.call('EXISTS', ARGV[1]) == 1 then
    return {0}
end

redis.call('DECR', KEYS[1])
redis.call('SET', ARGV[1], 'HOLD', 'EX', ARGV[2])
return {1}
```

> **본 feature 의 happy path 한정 의미**: 본 PR 시나리오는 *결제 항상 성공* 가정이라 hold 후 booking COMPLETED 까지 한 번에 진행. *결제 실패 후 재시도 = 같은 hold 재사용* 시나리오는 본 PR 미포함 — Saga+Outbox feature 에서 *"PG 실패 시 hold 유지 + idempotency-key 새 키 발급 후 재시도"* 본격 정의.

#### 3. Application — `BookingService` 흐름 보강

기존 `create()` 흐름에 다음 단계 삽입:

```
1. body hash 계산
2. idempotencyKeyService.checkAndReserve  // Redis SETNX 1차
   - PROCESSING/HASH_MISMATCH/COMPLETED: 기존 분기 그대로
   - NEW: 단계 3 진입
3. ★ stock.tryHold(accommodationId, userId, 300)  // 신규 단계
   - false: idempotencyKeyService.releaseKey(idempotencyKey)  // Redis DEL — 클라이언트 새 키 재시도 허용
            throw new StockSoldOutException()  // GlobalExceptionHandler → 409
4. PaymentComposition 검증
5. PG 호출 (트랜잭션 밖) — *항상 성공 가정* (본 feature scope)
6. DB persist (booking + idempotency_key) — 트랜잭션
7. idempotencyKeyService.complete(...)  // Redis 갱신
   ※ stock release 호출 X — hold 가 booking 으로 전환됨 (booking COMPLETED ≡ hold 자원 점유)
```

**신규 / 변경 application 파일**:

| 파일 | 변경 |
|---|---|
| `IdempotencyKeyService` | `releaseKey(UUID key)` 메소드 추가 — Redis DEL only (DB 영속 X — NEW 단계라 DB INSERT 전) |
| `BookingService` | `StockRepository` 의존 주입 + 위 흐름 통합 (단계 3) |
| `application/StockSoldOutException` (신규) | 409 매핑 |
| `infrastructure/redis/IdempotencyLuaScript` | `releaseKey(UUID key)` 위임 메소드 추가 — `redisTemplate.delete(key)` 단일 명령 (Lua 불필요), fallback warn log |

#### 4. API — `GlobalExceptionHandler` 매핑 추가

```java
@ExceptionHandler(StockSoldOutException.class)
public ResponseEntity<Map<String, String>> handleSoldOut(StockSoldOutException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", "SOLD_OUT — 재고가 모두 소진되었습니다. 새로고침 후 재시도해 주세요."));
}
```

> **CONFLICT (409) 선택 근거**: ADR-008 §재고 풀림 시 분배 정책 — *"새로고침 폴링으로 재참여"*. 409 는 *"resource state 충돌"* 시맨틱 정합. 422 (Unprocessable) 는 *"입력 검증 실패"* 영역이라 부적합.

#### 5. 후속 feature 추가 비용 (memory: 확장성 우선)

| 후속 변경 | 추가 비용 |
|---|---|
| **TTL 만료 sweeper** (booking HOLD 5분 초과 자동 cleanup + stock INCR) | ShedLock 인프라 도입과 함께 sweeper 1개 — Saga+Outbox feature 와 묶음 |
| **PG 4XX 시 hold 유지 + idempotency 재시도 흐름** (ADR-008 amendment 정합) | BookingService 에 try-catch + idempotency_key 새 키 발급 패턴 추가. Saga+Outbox feature 에서 본격화 |
| 객실 타입별 재고 (예: 더블/트윈 분리) | `stock:accommodation:{id}:{roomType}` 키 시그니처 확장 — Lua 변경 0, adapter signature 1 변경 |
| Redis 외 저장소 (예: PostgreSQL row lock) | `StockRedisAdapter` → `StockJpaAdapter` 교체 — port 인터페이스 그대로. 단 atomic DECR 정확도는 RDB 측에서 별도 보장 필요 |
| 재고 초기화 admin API | `init(...)` port 메소드 그대로 + Controller 1개 추가 |

#### 6. CRITICAL 제약 (CLAUDE.md §1-liner)

- **#2 Redis 원자 연산은 Lua Script** — `stock_hold.lua` 단일 스크립트 atomic. WATCH/MULTI 금지.
- **#5 Redis Fail-Closed → 503** — `tryHold` fallback 은 `RedisUnavailableException` throw. Fail-Open (재고 무시 진입) 절대 금지.
- **#1 PG 호출은 DB 트랜잭션 밖** — 기존 패턴 유지. stock tryHold 도 트랜잭션 밖 (Redis 호출).

#### 7. 영향 받는 기존 테스트

- `BookingIdempotencyIntegrationTest` — 본 PR 의 stock 통합 후, **fixture 에 stock 초기화** 필요 (`@BeforeEach` 에서 `redisTemplate.opsForValue().set("stock:accommodation:42", "10")`). 통합 후 모든 시나리오 GREEN 유지.
- `BookingIdempotencyConcurrencyTest` — 동시 100 동일 키 → 1건 성공 시나리오. stock 도입 후에도 GREEN — 단 fixture 에 stock 충분히 (>=1) 초기화.

### Phase 2: RED — Failing Tests

- **위임**: `test-author` agent (또는 main claude 인라인) — Pattern 3 (Lua 동시성).
- **테스트 파일**:
  - `src/test/java/com/booking/integration/BookingStockIntegrationTest.java` — Scenario 1~3.
  - `src/test/java/com/booking/concurrency/BookingStockConcurrencyTest.java` — Scenario 4 (100 동시 요청, ExecutorService + CountDownLatch).
- **base class 결정**: `extends IntegrationTestSupport` — `BookingIdempotencyIntegrationTest` 와 동일 cache key (PG `external.pg.url` `@DynamicPropertySource`) 공유로 Spring context cache hit, 다중 test class 동시 실행 시에도 race 없음. PR #12 의 standalone fallback 은 PG 의존 없는 `CheckoutIntegrationTest` 한정 (cache key 다름).
- **mocking 경계**: WireMock(PG) — 본 feature 는 *PG 항상 성공* stub. Testcontainers(MySQL+Redis).
- **검증 커맨드** (모두 fail 해야 RED):
  ```bash
  ./gradlew test --tests "com.booking.integration.BookingStockIntegrationTest"
  ./gradlew test --tests "com.booking.concurrency.BookingStockConcurrencyTest"
  ```
- **AC**: 4 시나리오 모두 fail (production 미구현). 컴파일 성공.

### Phase 3: GREEN — Sub-phase 분할 (3 레이어)

#### Phase 3.1: Stock Domain Port + Lua + Redis Adapter

- **작성 대상**:
  - `domain/stock/StockRepository.java` (port)
  - `application/StockSoldOutException.java`
  - `infrastructure/redis/StockRedisAdapter.java` (`@Component`, Resilience4j wrapping)
  - `src/main/resources/lua/stock_hold.lua`
- **CRITICAL 제약**: Lua atomic (WATCH/MULTI 금지). Resilience4j `redisOps` cache name 재사용 (별도 cache 추가 X — 같은 instance bulkhead 공유로 Redis 보호 일관).
- **검증 커맨드**: `./gradlew compileJava compileTestJava`
- **AC**: 컴파일 성공.

#### Phase 3.2: BookingService 흐름 통합 + IdempotencyKeyService.releaseKey

- **작성 대상**:
  - `application/IdempotencyKeyService.java` — `releaseKey(UUID key)` 추가 (Redis DEL).
  - `application/BookingService.java` — `StockRepository` 의존 주입 + 위 §3 흐름 통합 (단계 3).
  - `api/GlobalExceptionHandler.java` — `StockSoldOutException` → 409 매핑.
  - `infrastructure/redis/IdempotencyLuaScript.java` — `releaseKey(UUID key)` 메소드 (단일 DEL, fallback warn log).
- **CRITICAL 제약**:
  - PG 호출 전 stock hold (트랜잭션 밖).
  - SOLD_OUT 시 idempotency key DEL — DB INSERT 전이라 DB 영속 무관.
- **검증 커맨드**: `./gradlew compileJava compileTestJava`
- **AC**: 컴파일 성공.

#### Phase 3.3 (마지막): API + Integration GREEN

- **작성 대상**: 변경 X — Phase 3.1/3.2 변경 통합으로 충분.
- **fixture 변경**:
  - `IntegrationTestSupport.seedAndCleanFixtures` 에 stock seed 추가 — `redisTemplate.opsForValue().set("stock:accommodation:42", "10")` + hold key cleanup. 모든 통합/동시성 test 기본값.
  - `BookingStockIntegrationTest` 의 boundary 시나리오 (재고 1, 0) 는 자체 @BeforeEach 에서 stock 값 override.
  - `BookingStockConcurrencyTest` 는 100 명 사용자 (userId 10000~10099) 추가 batch INSERT — booking.user_id FK 만족.
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests "com.booking.integration.BookingStockIntegrationTest"
  ./gradlew test --tests "com.booking.concurrency.BookingStockConcurrencyTest"
  ./gradlew test    # 기존 테스트 미파괴
  ```
- **AC**: 4 시나리오 모두 GREEN. 기존 `BookingIdempotencyIntegrationTest` 6/6 + `BookingIdempotencyConcurrencyTest` 1/1 + 기타 GREEN 유지.

### Phase 4: REFACTOR

- **위임**: main claude 인라인 (구조 변경 ≤ 지엽적).
- **검토 포인트**:
  - `StockRedisAdapter` 와 `IdempotencyLuaScript` 의 Lua loader 패턴 중복 — 공통 base / helper 추출 검토 (단, 추출 비용 < 가치 일 때만).
- **검증 커맨드**: `./gradlew test` GREEN 유지.
- **AC**: 모든 기존 테스트 GREEN. 새 테스트 추가 금지.

### Phase 5: Review

- **위임**: `java-reviewer` — Spring Boot 패턴 / Resilience4j wrapping. DB DDL 변경 0 → `database-reviewer` skip.
- **검증 커맨드**:
  ```bash
  ./gradlew verify
  git diff main...HEAD
  ```
- **AC**: CRITICAL/HIGH 0건. MEDIUM 이하 본 feature 범위 내 처리 또는 follow-up.

### Phase 6: Concurrency / Load Verification

- **트리거 조건**: ADR-002/008 — 의무 영역 (재고 정확성 / Lua atomic).
- **위임**: `test-author` Pattern 3 (Lua 동시성).
- **도구**: ExecutorService(100 threads) + CountDownLatch + Testcontainers.
- **검증 메트릭**:
  - oversell == 0 (Redis stock 음수 0건, booking row count == 10)
  - SOLD_OUT count == 90 (정확)
- **검증 커맨드**: `./gradlew test --tests "com.booking.concurrency.BookingStockConcurrencyTest"`
- **AC**: Scenario 4 GREEN. oversell 0 / booking count 10 / SOLD_OUT 90 정확.

> k6 부하 테스트는 본 feature out-of-scope — feature-005+ Rate Limit 영역과 묶음.

---

## Out of Scope

본 feature 가 의도적으로 다루지 않는 영역. 후속 feature 진입 시 통합:

### 1. PG 결제 실패 흐름 — `DECISIONS.md` §11 참조

| 케이스 | 처리 위치 |
|---|---|
| 케이스 1 (PG 거절 4XX) — *hold 유지 + 400 + 재시도* | **Saga+Outbox feature** — BookingService 에 try-catch + idempotency_key 새 키 발급 + 사용자 응답 메시지 추가. ADR-008 amendment 정합 |
| 케이스 2 (PG Timeout 5XX) — UNKNOWN + reconciliation | **Reconciliation feature** (ADR-011) — @Scheduled + ShedLock 인프라 위 |
| 케이스 3 (DB 커밋 실패) — Saga 보상 | **Saga+Outbox feature** (ADR-009/010) — Outbox compensation_payload + PG 취소 |

본 feature 는 *PG 항상 성공* 가정으로 happy path + 재고 정확성 (동시성 100 요청) 만 검증. PG 4XX/5XX 분기는 BookingService 의 try-catch 패턴 도입 시점 = Saga feature 진입 시점.

### 2. TTL 만료 sweeper

ADR-008 amendment §본 정정의 후속 영향 — *"booking HOLD status 5분 초과 자동 cleanup + stock INCR"* sweeper 의무화. 본 feature 는 *hold key 의 Redis TTL set* 만 (`SET ... EX 300`). 5분 만료 시 Redis 가 hold key 자동 삭제하지만 *stock INCR* 은 별도 sweeper 가 booking row 도 함께 정리. ADR-010 ShedLock 인프라 도입과 함께 진입 — Saga+Outbox feature 와 묶음.

### 3. Booking 상태 머신 본격 전이

본 feature 는 booking row 를 status `COMPLETED` 직접 생성 (feature-001 패턴 그대로). `HOLD → PG_PENDING → COMPLETED` 전이는 PaymentAttempt 도메인 도입 + Saga+Outbox feature 와 묶음.

### 4. 같은 사용자 hold 재사용 흐름

ADR-008 amendment §재고 풀림 시 분배 정책 amendment — *"booking COMPLETED 사용자만 재진입 불가. 결제 실패 후 재시도 = hold 재사용 허용"*. 현 Lua 의 *"hold key EXISTS 면 차단"* 은 본 feature happy path 만에선 영향 없으나 (한 사용자가 같은 상품에 한 번만 시도), Saga feature 진입 시 *"hold key 살아있으면 booking 진행 단계로 진입 가능"* 분기 추가 필요. **본 feature 는 첫 시도만 처리, 재시도 처리는 Saga feature**.

### 5. 재고 초기화 admin API

운영 환경에서 accommodation 추가 시 stock SET. 본 PR 은 fixture/seed 의존. `init(...)` port 메소드는 정의만, Controller 는 admin API feature 진입 시 추가.

---

## Progress Log

(append-only — phase 완료 시 한 줄씩 추가)

- 2026-05-04 — Plan populated by main claude (covered ADRs: ADR-002, ADR-007, ADR-008 amendment, ADR-014). `tdd-planner` agent skip — 사용자 *"빠르게"* directive 정합.
- 2026-05-04 — REQUIREMENTS §1.2 정합 검토로 ADR-008 amendment 트리거 → plan 의 *"PG 실패 → stock release"* 흐름 제거, *"hold 유지 + sweeper out-of-scope"* 모델로 정정.
- 2026-05-04 — Phase 2 RED done — `BookingStockIntegrationTest` 3 + `BookingStockConcurrencyTest` 1 = 4/4 fail (production 미구현). base class = `IntegrationTestSupport` extend (cache key 정합 — race 없음).
- 2026-05-04 — Phase 3.1 GREEN done — `StockRepository` (port) + `StockSoldOutException` + `stock_hold.lua` + `StockRedisAdapter` (Resilience4j wrapping). 컴파일 GREEN.
- 2026-05-04 — Phase 3.2 GREEN done — `BookingService.create()` 단계 3 (tryHold) 통합. SOLD_OUT 시 `idempotencyKeyService.releaseKey` + `StockSoldOutException` throw. `IdempotencyLuaScript.releaseKey` + `IdempotencyKeyService.releaseKey` 추가. `GlobalExceptionHandler` 409 매핑.
- 2026-05-04 — Phase 3.3 GREEN done — `IntegrationTestSupport.seedAndCleanFixtures` 에 stock=10 seed + hold key cleanup 추가. 4/4 신규 test GREEN + 전체 `./gradlew test` BUILD SUCCESSFUL (회귀 0건).

---

## Outcome (feature Done 시 채움)

- **Files created/modified**:
  - 신규: `domain/stock/StockRepository`, `application/StockSoldOutException`, `infrastructure/redis/StockRedisAdapter`, `lua/stock_hold.lua`
  - 수정: `application/BookingService`, `application/IdempotencyKeyService` (releaseKey), `infrastructure/redis/IdempotencyLuaScript` (releaseKey), `api/GlobalExceptionHandler`
  - 테스트 신규: `BookingStockIntegrationTest`, `BookingStockConcurrencyTest`
  - 테스트 수정: 기존 `BookingIdempotency*Test` 의 fixture 에 stock seed 추가
- **Tests added**:
  - Integration: 3 (happy 1 + edge:boundary 2)
  - Concurrency: 1 (edge:concurrency)
- **ADR validation**:
  - ADR-008 amendment §결제 실패 시 hold 유지 — 본 feature 는 happy path 만 다루므로 hold 유지 흐름은 Saga feature 가 본격 검증
  - ADR-002 §Lua atomic — WATCH/MULTI 미사용
  - ADR-007 §Fail-Closed — `tryHold` Resilience4j fallback → `RedisUnavailableException` → 503
- **Follow-up**:
  - **Saga+Outbox feature** — PG 4XX/5XX 분기 + TTL sweeper + booking 상태 머신 본격 전이
  - **Reconciliation feature (ADR-011)** — PG Timeout UNKNOWN 처리
  - **재고 초기화 admin API** — `init(...)` port 메소드 활용
  - **객실 타입별 재고** — 키 시그니처 확장

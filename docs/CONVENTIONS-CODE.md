# Conventions — Code

코드 (Java / SQL / 테스트 본문) 작성 시 적용. 파일 생성·구조 컨벤션은 `CONVENTIONS-FILE.md` 참조.

본 파일은 **단일 진실의 원천**. ADR/agent에서 동일 컨벤션을 인용할 때 본 파일을 ref로 가리킨다.

---

## §1. Java Naming

| 종류 | 규칙 | 예시 |
|---|---|---|
| Class / Interface | `PascalCase` | `BookingService`, `IdempotencyKey` |
| Method (production) | `camelCase` | `createBooking()`, `validateBodyHash()` |
| Method (test) | `should_<expected>_when_<condition>` snake_case 허용 | `should_return_409_when_idempotency_key_in_processing` |
| Constant (`static final`) | `UPPER_SNAKE_CASE` | `MAX_RETRIES`, `STOCK_TTL_SECONDS` |
| Package | `lowercase` (단일 단어 권장) | `com.booking.domain.booking` |
| Type parameter (Generic) | 단문자 대문자 | `T`, `E`, `R` (특별한 의미 시 `KEY`, `VALUE`) |
| Boolean variable / method | `is`/`has`/`can` prefix | `isProcessing()`, `hasExpired()`, `canCancel()` |

---

## §2. Hexagonal Port / Adapter 네이밍 (ADR-014)

| 종류 | 네이밍 | 위치 | 예시 |
|---|---|---|---|
| Driving port | (interface) `<UseCase>UseCase` 또는 `<Aggregate>Service` | `application/` | `BookingService` |
| Driving adapter | `<Aggregate>Controller` | `api/` | `BookingController` |
| Driven port (persistence) | `<Aggregate>Repository` (interface) | `domain/<aggregate>/` | `BookingRepository`, `OutboxRepository` |
| Driven port (외부 시스템) | `<Action>Port` 또는 의미 interface | `domain/<aggregate>/` | `EventPublisher`, `ExternalPaymentMethod` |
| Driven adapter (persistence) | `<Aggregate>JpaRepository` | `infrastructure/persistence/` | `BookingJpaRepository` |
| Driven adapter (외부 시스템) | `<System><Role>Adapter` 또는 의미 class | `infrastructure/<system>/` | `TossPgAdapter`, `MockPgClient`, `InProcessEventPublisher`, `CardPayment` |

**원칙**: Domain은 외부 기술 의존 0 (JPA / Spring / Redis client import 금지). Driven adapter만 외부 기술 사용.

---

## §3. API Design (REST)

### HTTP Status Codes (ADR ref 강제)

| Status | 의미 | 근거 ADR |
|---|---|---|
| 200 | 성공 / 멱등성 캐시 응답 | ADR-006 |
| 202 | 비동기 처리 시작 (CancellationIntent 등) | ADR-011 |
| 400 | 도메인 invariant 위반 (예: PaymentComposition) | ADR-009 |
| 409 | 멱등성 키 처리 중 (PROCESSING 상태) | ADR-006 |
| 422 | 멱등성 키 payload 불일치 | ADR-006 |
| 429 | Rate Limit 초과 | ADR-005 |
| 503 | Redis Fail-Closed | ADR-007 |

### Endpoint 네이밍

- 명사 복수형: `/bookings` (단, 본 프로젝트 명세 `/booking` 단수 사용 — 명세 우선)
- Sub-resource: `/booking/{id}/cancel`
- Admin: `/admin/<resource>/<action>` (예: `/admin/circuit-breaker/{name}/open`)

### DTO 네이밍

- Request: `<Action><Entity>Request` — `CreateBookingRequest`, `CancelBookingRequest`
- Response: `<Entity>Response` — `BookingResponse`, `CheckoutResponse`
- 도메인 모델 직접 노출 금지 (DTO 변환 강제)

### Validation

- `@Valid` 강제 (Bean Validation) — Controller 메소드 파라미터에 누락 시 `java-reviewer` 차단
- DTO 필드에 `@NotNull` / `@NotBlank` / `@Min` / `@Max` / `@Pattern` 명시

---

## §4. Method 시그니처

- **`Optional<T>` 우선** — null 반환 금지 (`application` 레이어 강제). domain의 lookup-by-id 등.
- **필수 param 먼저, optional param 마지막**
- **≥4 param이면 builder 또는 record 고려** — `record CreateBookingCommand(...)` 같은 immutable 입력 객체
- **Side effect 명시** — 메소드명에 동작 명시 (`save`, `delete`, `cancel`, `publish`)

---

## §5. Exception

### 도메인 예외 클래스

- 네이밍: `<Domain><Reason>Exception`
- 예시: `InvalidPaymentCompositionException`, `BookingNotFoundException`, `IdempotencyKeyMismatchException`
- 위치: `domain/<aggregate>/exception/` 또는 해당 도메인 root

### 중앙화

- `@RestControllerAdvice` `GlobalExceptionHandler` — 모든 도메인 예외 → HTTP status 변환
- 변환 매핑은 ADR cross-ref 강제 (예: `IdempotencyKeyMismatchException` → 422 — ADR-006)

### Stack trace

- `log.error(message, exception)` 항상 — 두 번째 인자로 exception 전달 (stack trace 보존)
- 도메인 예외는 사용자 메시지만 응답에 포함, stack trace는 로그에만

---

## §6. Test Code

### 기본 형식

```java
// Scenario: [edge:tampering] 같은 키 + 다른 body → 422
// Source: docs/features/feature-001-idempotency-handling.md
@Test
@Tag("edge")
@Tag("edge:tampering")
@DisplayName("같은 키 + 다른 body → 422")
void should_return_422_when_body_hash_differs() {
    // Given: ...
    // When: ...
    // Then: ...
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
}
```

### 메소드 네이밍

- `should_<expected>_when_<condition>` snake_case
- Scenario Map의 `Test Method` 컬럼과 정확히 일치 (Phase 2 검증 커맨드 `mvn test -Dtest=...#<method>` 가 메소드를 직접 가리킴)

### `@Tag` 매핑 (ADR-013)

- `[happy]` → `@Tag("happy")`
- `[edge:CATEGORY]` → `@Tag("edge")` + `@Tag("edge:CATEGORY")` (1:N — 모든 edge 공통 + 세부 카테고리)
- 실행 필터링: `mvn test -Dgroups=edge:concurrency`, `mvn test -Dgroups=happy`

### `@DisplayName`

- Scenario description (type prefix 제외, 한글 OK)
- 실패 리포트에서 가독성 ↑

### Given / When / Then 블록

- 명시적 주석으로 구획: `// Given:`, `// When:`, `// Then:`
- 한 메소드 = 한 시나리오 (1:1 with Gherkin Scenario)

### Traceability 주석

- 메소드 위에:
  - `// Scenario: [type] <라벨>` (feature 파일 라벨 그대로)
  - `// Source: docs/features/feature-NNN-...md`

### Assertions

- **AssertJ 강제**: `assertThat(...)`
- 예외 검증: `assertThatThrownBy(() -> ...).isInstanceOf(X.class).hasMessageContaining(...)`
- JUnit `assertEquals` 단독 사용 금지 (가독성 + 메시지 명확성)
- `@Test(expected = X.class)` JUnit 4 스타일 금지

### Test Data Builders

- 위치: `src/test/java/com/booking/testsupport/<Aggregate>TestDataBuilder.java`
- Fluent API: `new BookingTestDataBuilder().withStatus(HOLD).withAmount("50000").build()`
- 자주 쓰는 시나리오는 `static` factory: `aHoldBooking()`, `aPgPendingBooking()`
- Default 값은 valid (생성 즉시 도메인 invariant 만족)

### Edge Case 의무 (ADR-013)

- 모든 feature는 ≥1 `[edge:*]` Scenario 포함 의무
- 6 카테고리: `boundary` / `failure` / `concurrency` / `tampering` / `expiry` / (필요 시 새 카테고리)

### Mocking 정책

- 외부 시스템 (PG): **WireMock** (실제 HTTP, fault simulation)
- Redis / MySQL: **Testcontainers** (Embedded·mock 금지)
- 내부 의존성: **Mockito** (`@ExtendWith(MockitoExtension.class)`)

### 슬라이스 테스트 분담

| Annotation | 용도 |
|---|---|
| `@WebMvcTest(<Controller>.class)` | Controller 슬라이스. Service mock. |
| `@DataJpaTest` | Repository 슬라이스. Testcontainers MySQL 권장 (H2 거짓 통과 위험). |
| `@SpringBootTest` | 풀 컨텍스트 — Saga·Outbox·Reconciliation 통합 테스트만. **남발 금지**. |

---

## §7. SQL / DDL 코드

### 멱등 INSERT (ADR-010 amendment)

- **표준**: `INSERT ... ON DUPLICATE KEY UPDATE col = col`
- **금지**: `INSERT IGNORE` — 데이터 truncation·FK 위반 등 다른 에러도 함께 무시 (너무 광범위)

### 타입 매핑 (MySQL 8.0+ — ADR-013 §결정의 한계)

| 도메인 타입 | MySQL 타입 |
|---|---|
| ID (surrogate) | `BIGINT UNSIGNED AUTO_INCREMENT` |
| UUID | `BINARY(16)` + `UUID_TO_BIN(?, 1)` (시간 정렬) / `BIN_TO_UUID(col, 1)` |
| 금액 | `DECIMAL(15, 2)` (`FLOAT` / `DOUBLE` 금지) |
| 시각 | `TIMESTAMP` (UTC 가정) |
| Status enum | `VARCHAR(20)` + 앱 enum 매핑 (MySQL `ENUM` 타입 비권장 — `ALTER TABLE` 부담) |
| Boolean | `TINYINT(1)` (MySQL `BOOLEAN`은 alias) |
| JSON / 스냅샷 | `JSON` |
| 단문 | `VARCHAR(N)` (N = 실제 max), 큰 본문은 `TEXT` |
| Hash (SHA256 hex) | `CHAR(64)` |

### 식별자

- 컬럼·테이블: `lowercase_snake_case`
- ENUM 값: `UPPER_CASE` 문자열 (e.g., `'HOLD'`, `'PG_PENDING'`)

### 인덱스 네이밍

| 종류 | 형식 | 예시 |
|---|---|---|
| UNIQUE | `uk_<table>_<col>` | `uk_booking_idempotency_key` |
| 일반 | `idx_<table>_<col>` 또는 `idx_<table>_<col1>_<col2>` (복합) | `idx_booking_status_updated` |
| Foreign Key | `fk_<table>_<col>` | `fk_booking_user` |

### 인덱스 정책

- **FK 명시 인덱싱**: InnoDB가 FK에 자동 인덱스를 생성하지만, **명시 선언 강제** (`database-reviewer` agent 가이드)
- **`SELECT ... FOR UPDATE` 시 인덱스 적중 보장**: 인덱스 없으면 InnoDB가 테이블 전체 lock + gap lock 폭발
- **복합 인덱스 컬럼 순서**: equality 컬럼 먼저, range 컬럼 나중 (예: `(status, updated_at)`)
- **NULL 컬럼 인덱스**: InnoDB는 NULL 포함 저장 (PostgreSQL 부분 인덱스 미지원)

### `ENGINE` 선언

- 모든 테이블: `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`
- `MyISAM` / `utf8` / `utf8mb3` 사용 금지

---

## §8. Concurrency

### Redis Lua Atomic (ADR-002)

- "검사 + 변경"이 분리되면 race — Lua script로 atomic 처리
- `EVAL` 또는 `redisTemplate.execute(luaScript, ...)`
- `WATCH/MULTI/EXEC` 비권장 (CAS retry loop 부담)

### 동시성 테스트 (ADR-013 / ADR-008)

```java
ExecutorService pool = Executors.newFixedThreadPool(100);
CountDownLatch start = new CountDownLatch(1);  // 동시 시작 강제
CountDownLatch done = new CountDownLatch(1000);
AtomicInteger success = new AtomicInteger(0);

for (int i = 0; i < 1000; i++) {
    pool.submit(() -> {
        try {
            start.await();  // 모든 thread 대기 후 동시 진입
            // ... 호출 ...
            if (ok) success.incrementAndGet();
        } finally { done.countDown(); }
    });
}
start.countDown();
done.await(30, TimeUnit.SECONDS);

assertThat(success.get()).isEqualTo(expectedCount);
```

**원칙**:
- 100 thread × N iter 표준 (10 미만 luck-based 통과)
- `Testcontainers` 강제 (Embedded Redis 금지 — Lua atomicity 보장 안 됨)
- `start.countDown()` 동시 시작 강제 — 첫 thread만 race 노출 방지
- 결과 집계는 `AtomicInteger` / `ConcurrentHashMap` (동기화 안 된 카운터 금지)

### 시간 기반 테스트 (ADR-011)

- `Clock.fixed(Instant, ZoneOffset.UTC)` 주입 (Spring `@Bean @Primary`)
- `Thread.sleep(360_000)` 절대 금지 — Awaitility 사용
- DB의 `updated_at`을 직접 backdate (`Instant.now(clock).minus(7, MINUTES)`)
- 워커 직접 호출 (`reconciliationWorker.poll()`) — Spring `@Scheduled` 우회

### JCStress

- JIT / 메모리 visibility / 재정렬 의심 시만 도입 (ADR-013 §축 4)
- 일반 race condition은 ExecutorService로 충분

---

## §9. 도메인 패턴 cross-ref

상세 코드 예시는 `src/CLAUDE.md` (첫 코드 작성 시 채워질 stub).

| 패턴 | 근거 ADR | 핵심 원칙 |
|---|---|---|
| Saga | ADR-009 | PG 호출 → DB 트랜잭션 → 보상 (PG 취소 API). PG 호출은 트랜잭션 밖. |
| Outbox (Transactional) | ADR-010 | DB 트랜잭션 내 `outbox_event` INSERT. 폴러 `@Scheduled` + 분산 락. INSERT 실패 시 fallback 로깅 (롤백 X). |
| Consumer Idempotency | ADR-010 amendment | `INSERT INTO processed_event ... ON DUPLICATE KEY UPDATE event_id = event_id` 후 `ROW_COUNT()`로 분기. write-only trap 방어. |
| Reconciliation | ADR-011 | `@Scheduled + ShedLock` 패턴. PG 상태 조회 → CAS 전이. NOT_FOUND ≠ FAILED. retry N=3 + exponential backoff (30s→60s→120s) + jitter. |
| Idempotency (3-state) | ADR-006 | Redis 1차 (Lua atomic) + DB UNIQUE 2차. 200 (캐시) / 409 (처리 중) / 422 (payload 불일치). TTL 15분. |
| Stock Counter | ADR-008 | Redis atomic DECR + 5분 TTL. PG_PENDING 시 60초 추가 유예. 6분 도달 시 강제 회수 + PG 취소. |
| Rate Limit | ADR-005 | userId Token Bucket — Redis HMGET + 산술 + HSET (Lua atomic). |
| Circuit Breaker | ADR-007 | Resilience4j — 50% 실패율 → OPEN, 5초 윈도우. Fail-Closed: fallback에서 503. |

---

## §10. 의도적 비포함 (Out of Scope)

- **파일 위치 / 디렉토리 구조** — `CONVENTIONS-FILE.md` §4~§9 참조
- **Markdown 문서 형식** — `CONVENTIONS-FILE.md` §1 참조
- **Git branch / commit** — `scripts/README.md` (execute.py 자동화)
- **Agent 사용 시점** — `.claude/agents/<agent>.md`

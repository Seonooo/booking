# Feature 001: POST /booking 멱등성 처리 (ADR-006)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Draft | TBD | 2026-05-03 | 2026-05-03 |

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
| 1 | 신규 멱등성 키 → 200 OK + booking 생성 | happy | `should_create_booking_and_return_200_when_key_is_new` | `BookingIdempotencyIntegrationTest.java` | pending |
| 2 | 같은 키 + 같은 body, 처리 중 → 409 | happy | `should_return_409_when_same_key_in_processing_with_same_body` | `BookingIdempotencyIntegrationTest.java` | pending |
| 3 | 같은 키 + 같은 body, 이미 완료 → 200 + 캐시 | happy | `should_return_cached_response_with_200_when_completed_with_same_body` | `BookingIdempotencyIntegrationTest.java` | pending |
| 4 | 같은 키 + 다른 body → 422 | edge:tampering | `should_return_422_when_body_hash_differs` | `BookingIdempotencyIntegrationTest.java` | pending |
| 5 | 동시 동일 키 100건 → 1건만 성공, 99건 409 | edge:concurrency | `should_block_concurrent_same_key_requests` | `BookingIdempotencyConcurrencyTest.java` | pending |
| 6 | TTL 15분 만료 후 같은 키 재시도 → 200 | edge:expiry | `should_create_new_booking_when_key_expired_after_15_minutes` | `BookingIdempotencyIntegrationTest.java` | pending |
| 7 | Redis 장애 + DB unique 차단 | edge:failure | `should_block_duplicate_via_db_unique_when_redis_unavailable` | `BookingIdempotencyIntegrationTest.java` | pending |

**Edge case coverage**: 4/7 (57%) — `[edge:tampering]` 1, `[edge:concurrency]` 1, `[edge:expiry]` 1, `[edge:failure]` 1. ADR-013 §Edge Case 의무 조항 충족.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied ADRs**: ADR-006 (멱등성), ADR-002 (Lua atomic — Redis SETNX), ADR-007 (Redis 장애)
- **Test-first 의무 영역**: YES — 멱등성은 ADR-013 §의무 영역(application layer 멱등성 처리)에 명시
- **영향 엔티티 (`docs/ERD.md`)**: `idempotency_key`, `booking`
- **기존 패턴 참조**: ADR-006 §흐름 (Redis 1차 + DB 2차 이중 계층, body SHA256 검증)

### Phase 1: Architectural Blueprint

- [ ] pending
- **위임**: `code-architect`
- **요청**: 다음을 포함한 blueprint:
  - `IdempotencyKeyService` (application) — 키 검증·캐싱·3-state 응답 분기
  - `IdempotencyKeyRepository` (domain port + JPA adapter)
  - Redis Lua script (SETNX + body_hash 비교, atomic)
  - `BookingController.create()` 메소드 흐름 (멱등성 → stock DECR → PG → DB)
  - Body hash 계산 helper (SHA256(userId + productId + amount + paymentMethod + points))
- **검증 커맨드**:
  ```bash
  git diff --name-only                                    # 코드 변경 0건 (blueprint 문서만)
  ```
- **AC**: blueprint의 file path가 `docs/ARCHITECTURE.md` 디렉토리 구조에 정합. ERD `idempotency_key` 테이블 컬럼과 도메인 모델 일치.
- **결과**: ...

### Phase 2: RED — Failing Tests

- [ ] pending
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
  mvn test -Dtest=IdempotencyKeyServiceTest                       # Unit
  mvn test -Dtest=BookingIdempotencyIntegrationTest               # Integration (Scenario 1~4, 6, 7)
  mvn test -Dtest=BookingIdempotencyConcurrencyTest               # Concurrency (Scenario 5)
  # 단일 시나리오만:
  mvn test -Dtest=BookingIdempotencyIntegrationTest#should_return_422_when_body_hash_differs
  ```
- **AC**: 위 7 시나리오 테스트 모두 fail (production 미구현). 컴파일 성공.
- **결과**: ...

### Phase 3: GREEN — Minimal Implementation

5 레이어 변경 → **sub-phase 의무 분할** (≥3 레이어 임계, ADR-013 §Scope 최소화).
의존 순서: Domain → Application → Infrastructure → API.

#### Phase 3.1: Domain layer GREEN

- [ ] pending
- **작성 대상**:
  - `IdempotencyKey` Aggregate (status enum, body_hash 검증 메소드)
  - `IdempotencyKeyRepository` port (interface only)
- **CRITICAL 제약**:
  - Domain 불변식은 생성자에서 강제 (ADR-009 Domain VO 패턴)
  - Repository는 port (interface)만, 구현은 Phase 3.3
- **검증 커맨드**:
  ```bash
  mvn test -Dtest=IdempotencyKeyTest
  ```
- **AC**: `IdempotencyKeyTest` GREEN. Service/Integration 테스트는 RED 유지 (다른 레이어 미구현).
- **결과**: ...

#### Phase 3.2: Application layer GREEN

- [ ] pending
- **작성 대상**:
  - `IdempotencyKeyService` (3-state 응답 분기 로직: PROCESSING → 409 / COMPLETED+match → 200 cache / 미스매치 → 422)
  - `BodyHashCalculator` helper (SHA256(userId + productId + amount + paymentMethod + points))
- **CRITICAL 제약**:
  - 멱등성 검증은 트랜잭션 시작 전에 (ADR-006 흐름)
  - Repository는 Mockito mock으로 격리 (Infrastructure 미구현 상태)
  - PG 호출은 트랜잭션 밖 (CLAUDE.md CRITICAL #1)
- **검증 커맨드**:
  ```bash
  mvn test -Dtest=IdempotencyKeyServiceTest
  ```
- **AC**: `IdempotencyKeyServiceTest` GREEN. Integration 테스트는 여전히 RED.
- **결과**: ...

#### Phase 3.3: Infrastructure layer GREEN

- [ ] pending
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
  mvn test -Dtest=IdempotencyLuaScriptTest        # Slice + Testcontainers Redis
  mvn test -Dtest=IdempotencyKeyJpaRepositoryTest # Slice + Testcontainers MySQL
  ```
- **AC**: 두 slice 테스트 GREEN. Lua atomic 동작 + DB UNIQUE 충돌 시 적절한 예외.
- **결과**: ...

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
  mvn test -Dtest=BookingIdempotencyIntegrationTest
  mvn test -Dtest=BookingIdempotencyConcurrencyTest
  mvn test                                              # 기존 테스트 미파괴 확인
  ```
- **AC**: Phase 2 RED의 7 시나리오 모두 GREEN. 기존 모든 테스트 미파괴.
- **결과**: ...

### Phase 4: REFACTOR

- [ ] pending
- **위임**: 호출자 (지엽적 — 명명·중복 정리)
- **검증 커맨드**:
  ```bash
  mvn test                                                        # 전체 테스트 GREEN 유지
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
  mvn verify                                                      # 전체 통합 (test + integration profile)
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
  mvn test -Dgroups=edge:concurrency                              # 동시성 edge case 전체
  mvn test -Dtest=BookingIdempotencyConcurrencyTest               # 본 feature만
  ```
- **AC**: `should_block_concurrent_same_key_requests` 통과 + 메트릭 충족 (200 정확히 1, 409 정확히 99, DB row 1, stock decrement 1).
- **결과**: ...

---

## Progress Log

(작성 시작 시 한 줄씩 append)

- 2026-05-03 — Feature file pre-written as convention demo. Status remains Draft until tdd-planner takes over.

---

## Outcome (feature Done 시 채움)

- **Files created/modified**: TBD
- **Tests added**: TBD
- **ADR validation**: ADR-006 매트릭스 row "body_hash 일치/불일치 / 3-state 응답 / 동시 동일 키"의 시나리오 7개 모두 테스트로 매핑 (계획)
- **Follow-up**: 멱등성 키 만료 정리 배치(`expires_at < NOW()`)는 별도 feature로 분리

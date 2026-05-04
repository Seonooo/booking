# Feature 004: Saga + booking 상태 머신 본격 + PaymentAttempt 도메인 + outbox_event INSERT (DECISIONS.md §11)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| In-Progress | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 모든 컨텍스트(REQUIREMENTS / ADR / ERD / 영향 코드 경로 / Pattern 번호) 인라인. 외부 대화 참조 금지.

## Request

> 사용자: *"내가 전달 준 API 구현을 최우선 목적으로 빠르게 작업해보자"* + *"다음 작업들은 병렬 처리가 가능한 부분이 생긴다면 병렬처리 고려해봐"*. feature-005 (Outbox 폴러 + Saga 보상) / feature-006 (TTL sweeper) / feature-007 (Reconciliation worker) 의 prerequisite 박는 단일 feature.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `docs/REQUIREMENTS.md` §1.2 / Req 5 | POST /booking = 결제 진행 + 최종 주문 생성 / 결제 실패 (한도 초과) 대응 로직 설계 |
| `docs/adr/DECISIONS.md` §11 | 결제 실패 3종 분류 — 케이스 1 (PG 거절 4XX) / 케이스 2 (PG Timeout 5XX/timeout) / 케이스 3 (DB 커밋 실패) |
| `docs/adr/DECISIONS.md` §11 amendment 2026-05-04 | 케이스 1 → hold 유지 (TTL sweeper 가 INCR) |
| `docs/adr/ADR-008-stock-counter.md` amendment 2026-05-04 | 결제 실패 시 hold 유지 + 결제 시도자 UX 우선 |
| `docs/adr/ADR-009-payment-extensibility.md` | PG 호출 → DB 트랜잭션 (Saga). DB 실패 시 Saga 보상 (PG 취소) |
| `docs/adr/ADR-010-event-processing.md` | Transactional Outbox — booking save 와 같은 트랜잭션 안에서 outbox_event INSERT. Outbox INSERT 실패 시 fallback 로깅 (롤백 X — booking 보존 우선) |
| `docs/adr/ADR-011-pg-idempotency-reconciliation.md` | PaymentAttempt 도메인 entity. attempt_id (UUID) = PG 멱등성 헤더. Booking 멱등성 키와 분리 |
| `docs/ERD.md` §4.1, §4.2, §4.4, §6.1, §6.2 | booking / payment_attempt / outbox_event 스키마 + 상태 머신 |

## 본 feature scope 결정 — 옵션 X (권장 채택)

| 영역 | 본 feature | 후속 feature |
|---|---|---|
| booking 상태 머신 본격 (HOLD → PG_PENDING → COMPLETED/FAILED/UNKNOWN) | ✅ | — |
| PaymentAttempt 도메인 도입 (INIT → REQUESTED → SUCCESS/FAILED/TIMEOUT) | ✅ | — |
| outbox_event INSERT (booking COMPLETED 시 같은 트랜잭션) | ✅ | — |
| PG 호출 결과 분기 (2XX/4XX/5XX timeout) — DECISIONS.md §11 케이스 1·2·3 진입만 | ✅ | — |
| Saga 보상 (DB 실패 시 PG cancel 호출) | ❌ — booking 미생성 + 503 응답 (fallback 로깅) | feature-005 |
| Outbox 폴러 + ShedLock + 컨슈머 | ❌ | feature-005 |
| TTL 만료 sweeper (booking HOLD 5분 초과 + stock INCR) | ❌ | feature-006 |
| Reconciliation worker (PG 상태 조회 + UNKNOWN 결과 확정) | ❌ | feature-007 |
| 결제 실패 후 재시도 (hold 재사용 + 새 idempotency-key) | ❌ | feature-005 |

> **권장 채택 사유**: BookingService 의 *최종 흐름 (booking save 트랜잭션 + outbox INSERT)* 한 번에 박는 게 ADR-010 정합 (*"booking save 와 같은 트랜잭션 안에서 outbox_event INSERT"*). feature-005 가 BookingService 다시 변경하는 비용 회피. 후속 feature-005/006/007 은 *각자 worker 클래스* + *서로 다른 ShedLock 락* 영역 → **본 feature 머지 후 3 feature 병렬 진행 가능** (사용자 *"병렬 처리 고려"* directive 정합).

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태이고
  And   accommodation(id=42, name="테스트 숙소", base_price=50000)이 존재하며
  And   Redis stock:accommodation:42 = 10 으로 초기화돼 있다

Scenario: [happy] PG 성공 → booking COMPLETED + paymentAttempt SUCCESS + outbox_event INSERT
  Given Redis stock=10, PG 가 200 정상 응답
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 200 응답을 받고
  And   booking row 1건 (status=COMPLETED) 이 생성됐고
  And   payment_attempt row 1건 (status=SUCCESS, external_payment_id=set) 이 생성됐고
  And   outbox_event row 1건 (event_type=BookingCompleted, status=PENDING) 이 생성됐고
  And   stock=9, hold key TTL 5분

Scenario: [edge:failure] PG 4XX 거절 (한도 초과) → booking FAILED + paymentAttempt FAILED + outbox 미생성
  Given Redis stock=10, PG 가 400 응답 ({"code":"INSUFFICIENT_LIMIT"})
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 400 응답을 받고
  And   응답 메시지에 "결제가 거절되었습니다. 다른 결제 수단으로 재시도해 주세요" 가 포함되며
  And   booking row 1건 (status=FAILED) 이 생성됐고
  And   payment_attempt row 1건 (status=FAILED) 이 생성됐고
  And   outbox_event row 0건 이며
  And   stock=9 (hold 유지 — TTL sweeper 가 후속 INCR, ADR-008 amendment)
  And   idempotency_key Redis 에서 cleanup (사용자 새 키로 재시도 가능)

Scenario: [edge:failure] PG 5XX → booking UNKNOWN + paymentAttempt TIMEOUT
  Given Redis stock=10, PG 가 500 응답
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 503 응답을 받고
  And   응답 메시지에 "처리 중 — 잠시 후 결과 확인 부탁드립니다" 가 포함되며
  And   booking row 1건 (status=UNKNOWN) 이 생성됐고
  And   payment_attempt row 1건 (status=TIMEOUT) 이 생성됐고
  And   stock=9 (hold 유지 — Reconciliation worker 가 후속 결과 확정)
  And   idempotency_key Redis PROCESSING 유지 (같은 key 재시도는 409 — Reconciliation 결과 push 후 클라이언트 새로고침 폴링)

Scenario: [edge:failure] PG 호출 timeout (응답 미수신) → booking UNKNOWN
  Given Redis stock=10, PG 가 응답 timeout (ResourceAccessException)
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 503 응답을 받고
  And   booking row 1건 (status=UNKNOWN), payment_attempt status=TIMEOUT
  And   stock=9 (hold 유지)

Scenario: [edge:failure] PG 성공 후 DB 커밋 실패 → fallback 로깅 + booking 미생성 + 503
  Given Redis stock=10, PG 가 200 정상 응답
  And   DB 트랜잭션 commit 시 SQLException 발생 (예: 제약 위반 / 네트워크 단절 mock)
  When  사용자가 POST /booking 을 호출하면
  Then  HTTP 503 응답을 받고
  And   booking row 0건 (트랜잭션 롤백)
  And   payment_attempt row 0건
  And   outbox_event row 0건
  And   로그에 [SAGA_COMPENSATION_PENDING] 마커 + externalPaymentId 가 기록되며
  And   stock=9 (hold 유지 — TTL sweeper 가 후속 INCR)
  Note: PG 취소 호출 (Saga 보상) 본격은 feature-005 영역. 본 시나리오는 *"운영자 인지 가능한 마커 로깅"* 만 검증.
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | PG 성공 → booking COMPLETED + outbox INSERT | happy | `should_complete_booking_and_insert_outbox_when_pg_success` | `BookingSagaIntegrationTest.java` | RED |
| 2 | PG 4XX → booking FAILED + 400 | edge:failure | `should_mark_booking_failed_and_return_400_when_pg_rejects` | `BookingSagaIntegrationTest.java` | RED |
| 3 | PG 5XX → booking UNKNOWN + 503 | edge:failure | `should_mark_booking_unknown_when_pg_5xx` | `BookingSagaIntegrationTest.java` | RED |
| 4 | PG timeout → booking UNKNOWN + 503 | edge:failure | `should_mark_booking_unknown_when_pg_timeout` | `BookingSagaIntegrationTest.java` | RED |
| 5 | PG 성공 + DB 실패 → fallback 로깅 + 503 | edge:failure | `should_log_saga_compensation_marker_when_db_fails_after_pg_success` | `BookingSagaCompensationTest.java` | GREEN (production fail-safe 흐름 정합 — 시나리오 5 의 *fallback 로깅 마커* 검증은 본 PR scope 외) |

> **시나리오 5 가 별 test class 인 이유**: `@MockitoBean BookingRepository` 가 모든 시나리오에 적용되면 시나리오 1~4 의 *real save 흐름* 이 망가진다. Spring context cache key 정합 — mock 사용 시 별 cache (별 class 분리). 시나리오 1~4 는 일반 IntegrationTestSupport 정합.

**Edge case coverage**: 4/5 (80%) — `[edge:failure]` ×4. ADR-013 §Edge Case 의무 + DECISIONS.md §11 케이스 1·2·3 모두 cover.

> **본 feature 는 동시성 의무 영역 X** — 동시성은 ADR-002/006/008 영역으로 feature-001/003 가 이미 cover. 본 feature 는 *상태 머신 + 결제 실패 분기* 정합성 위주.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied**: REQUIREMENTS §1.2 / Req 5 / DECISIONS.md §11 / ADR-008 amendment / ADR-009 / ADR-010 / ADR-011 / ADR-014.
- **Test-first 의무 영역**: **YES** — Saga (ADR-009) + Outbox INSERT (ADR-010) 의 의무 영역. 단 보상 / 폴러 / sweeper / reconciliation 미포함이라 의무 영역의 *진입 부분만*.
- **영향 엔티티 (`docs/ERD.md`)**: `booking` (상태 머신 본격) / `payment_attempt` / `outbox_event`. **V1__init.sql 이 이미 7 테이블 모두 포함** — 본 PR 신규 마이그레이션 X.
- **현재 코드 상태**:
  - `BookingService.create()` 가 *idempotency check → stock tryHold → PG → DB persist (status COMPLETED 직접) → idempotency complete*. PG 4XX/5XX 분기 없음. paymentAttempt 도메인 X. outbox_event INSERT X.
  - `BookingStatus` enum 이 `HOLD / PG_PENDING / COMPLETED / FAILED / UNKNOWN` 정의되어 있으나 현재 `COMPLETED` 만 사용.
  - `CardPayment.execute()` 가 RestTemplate POST `/payment` — 4XX/5XX 분기 미구현 (현재 `RuntimeException` throw 단일).

### Phase 1: Architectural Blueprint

#### 1. 도메인 — `domain/payment_attempt/`

| 파일 | 역할 |
|---|---|
| `PaymentAttempt` (aggregate, immutable record) | id (Long, nullable for new), attemptId (UUID), bookingId (Long), amount (BigDecimal), paymentCompositionSnapshot (String JSON), status (`PaymentAttemptStatus`), externalPaymentId (String, nullable), lastRequestedAt (Instant, nullable), createdAt (Instant), updatedAt (Instant) |
| `PaymentAttemptStatus` enum | `INIT` / `REQUESTED` / `SUCCESS` / `FAILED` / `TIMEOUT` (ERD §6.2 정합. `ACKED` 본 PR 미사용 — 동기 HTTP 가정) |
| `PaymentAttemptRepository` (port) | `PaymentAttempt save(PaymentAttempt)`, `int casToRequested(long id)`, `void updateToTerminal(long id, PaymentAttemptStatus status, String externalPaymentId)` |

**CAS pattern** (ADR-011 §retry CAS):
```sql
UPDATE payment_attempt
SET status = 'REQUESTED', last_requested_at = NOW()
WHERE id = ? AND status IN ('INIT', 'TIMEOUT');
```
ROW_COUNT == 1 → 진입 성공. ROW_COUNT == 0 → 이미 다른 thread 가 진입 중 (본 feature happy path 에선 발생 X — concurrency 검증 시나리오 미포함).

#### 2. 도메인 — `domain/outbox/`

| 파일 | 역할 |
|---|---|
| `OutboxEvent` (aggregate, immutable record) | id (Long, nullable for new), eventType (String), idempotencyKey (UUID), payload (String JSON), status (`OutboxEventStatus`), createdAt (Instant), publishedAt (Instant, nullable) |
| `OutboxEventStatus` enum | `PENDING` / `PUBLISHED` (본 PR 은 PENDING INSERT 만, PUBLISHED 전이는 feature-005 폴러) |
| `OutboxEventRepository` (port) | `OutboxEvent save(OutboxEvent)` 단일 메소드 (본 PR scope) |

#### 3. 도메인 — `domain/booking/` 변경

| 파일 | 변경 |
|---|---|
| `Booking` (기존 record) | 변경 없음 — status field 그대로 |
| `BookingRepository` (port) | `int casToStatus(long bookingId, BookingStatus from, BookingStatus to)` 메소드 추가. CAS UPDATE 정합 |

CAS pattern:
```sql
UPDATE booking SET status = ?, updated_at = NOW()
WHERE id = ? AND status = ?;  -- ROW_COUNT == 1 → 진입 성공
```

#### 4. 인프라 — `infrastructure/persistence/`

| 파일 | 변경 |
|---|---|
| `PaymentAttemptJpaEntity` (신규) | ERD §4.2 매핑. `id` IDENTITY, `attempt_id` BINARY(16), `status` STRING enum, `external_payment_id` nullable, etc. |
| `PaymentAttemptJpaRepository` (신규) | `extends JpaRepository<PaymentAttemptJpaEntity, Long>` |
| `PaymentAttemptRepositoryAdapter` (신규) | `implements PaymentAttemptRepository`. `@Component`. `casToRequested` / `updateToTerminal` 은 `@Modifying @Query` 또는 `EntityManager.createNativeQuery` |
| `OutboxEventJpaEntity` (신규) | ERD §4.4 매핑. `id` IDENTITY, `event_type` VARCHAR, `idempotency_key` BINARY(16), `payload` JSON, `status` STRING enum |
| `OutboxEventJpaRepository` (신규) | Spring Data 기본 |
| `OutboxEventRepositoryAdapter` (신규) | `@Component`, `EntityManager.persist` (Spring Data save merge 회피, feature-001 패턴 정합) |
| `BookingRepositoryAdapter` (수정) | `casToStatus` 구현 추가 — `EntityManager.createNativeQuery(UPDATE ... WHERE id=? AND status=?).executeUpdate()` |

#### 5. 인프라 — `infrastructure/payment/CardPayment` 변경

PG 응답 분기 본격:

```java
public PaymentResult execute(PaymentRequest request) {
    try {
        ResponseEntity<...> response = restTemplate.postForEntity(...);
        // 2XX: 정상 흐름
        return new PaymentResult(externalPaymentId, "SUCCESS");
    } catch (HttpClientErrorException e) {
        // 4XX — 명확한 거절 (한도 초과 등)
        throw new PaymentRejectedException(e.getStatusCode().value(), e.getResponseBodyAsString(), e);
    } catch (HttpServerErrorException | ResourceAccessException e) {
        // 5XX / timeout — UNKNOWN 영역
        throw new PaymentTimeoutException("PG 5XX or timeout", e);
    }
}
```

**신규 `infrastructure/payment/`**:
| 파일 | 역할 |
|---|---|
| `PaymentRejectedException` (신규) | RuntimeException — PG 4XX. statusCode + responseBody 보존. application 가 catch → 400 |
| `PaymentTimeoutException` (신규) | RuntimeException — PG 5XX/timeout. application 가 catch → 503 + UNKNOWN 진입 |

> **CardPayment.cancel(externalPaymentId, cancelAmount)** 는 본 PR 미활성 (시그니처만 — Saga 보상 영역, feature-005 에서 본격).

#### 6. Application — `BookingService` 흐름 본격 변경

```
1. body hash 계산
2. idempotency check & reserve (기존 그대로)
3. stock tryHold (기존 그대로)
4. ★ DB 트랜잭션 1 — booking HOLD INSERT + paymentAttempt INIT INSERT + CAS REQUESTED + booking PG_PENDING (CAS)
5. ★ PG 호출 (트랜잭션 밖) — 결과별 분기:
   (a) 2XX: 단계 6
   (b) 4XX (PaymentRejectedException): catch → 단계 6-rejected
   (c) 5XX/timeout (PaymentTimeoutException): catch → 단계 6-timeout
6. ★ DB 트랜잭션 2 — 결과별 분기:
   (a) 성공: paymentAttempt SUCCESS + booking COMPLETED (CAS) + outbox_event INSERT
       - INSERT outbox_event 실패 시 fallback 로깅 (롤백 X — ADR-010 #6 booking 보존 우선)
       - DB commit 후 Redis idempotency complete (기존 그대로)
       - 응답 200 + bookingId
   (b) PG 4XX: paymentAttempt FAILED + booking FAILED (CAS) + outbox X
       - DB commit 후 idempotencyKeyService.releaseKey (Redis cleanup, 새 키 재시도 허용)
       - throw PaymentRejectedException → 400 응답
   (c) PG 5XX/timeout: paymentAttempt TIMEOUT + booking UNKNOWN (CAS) + outbox X
       - idempotency_key Redis PROCESSING 유지 (Reconciliation 결과 push 후 클라이언트 새로고침)
       - throw PaymentTimeoutException → 503 응답
   (d) DB 실패 (단계 6-a 안에서 SQLException): catch → fallback 로깅 [SAGA_COMPENSATION_PENDING] + externalPaymentId
       - throw RuntimeException → 503 응답
       - PG cancel 호출은 feature-005 에서 본격
```

**신규 application 파일**:
| 파일 | 변경 |
|---|---|
| `application/PaymentRejectedException` (신규) | RuntimeException — domain 영역 시맨틱 (단 infrastructure 의 same-name 과 별개? — 본 PR 은 domain 영역 X, infrastructure 의 PaymentRejectedException 그대로 application 까지 propagate). **결정**: infrastructure 의 PaymentRejectedException 만 정의. application/api 는 그대로 catch. |
| `BookingService` 변경 | 위 흐름 통합 — `~80 LOC 추가/변경` |

#### 7. API — `GlobalExceptionHandler` 매핑 추가

| Exception | HTTP | 비고 |
|---|---|---|
| `PaymentRejectedException` (infra) | **400** | DECISIONS.md §11 케이스 1 정합 — *"결제가 거절되었습니다 — 다른 결제 수단으로 재시도해 주세요"* 메시지 |
| `PaymentTimeoutException` (infra) | **503** | DECISIONS.md §11 케이스 2 정합 — *"처리 중 — 잠시 후 결과 확인 부탁드립니다"* 메시지 |

#### 8. DB 마이그레이션 — 신규 마이그레이션 ❌

**V1__init.sql 이 이미 7 테이블 모두 포함** (확인 완료, 2026-05-04). 본 PR 의 작업은 *기존 테이블에 JPA Entity + Adapter 추가* + BookingService 흐름 통합. 신규 V2 마이그레이션 작성 X.

ERD §8 의 DDL 그대로 — `payment_attempt` / `outbox_event` 컬럼 / 인덱스 / FK 모두 V1 에 정합. 본 plan 의 JPA Entity 매핑은 V1 스키마 그대로 따른다.

#### 9. CRITICAL 제약 (CLAUDE.md §1-liner)

- **#1 PG 호출 트랜잭션 밖** — DB 트랜잭션 1 (booking HOLD INSERT) + DB 트랜잭션 2 (booking COMPLETED + outbox INSERT) 사이에 PG 호출. 두 트랜잭션 명시적 분리 (`@Transactional` 분리 메소드 또는 `TransactionTemplate`).
- **#3 Outbox 폴러 분산 락** — 본 PR 미적용 (폴러 미구현). feature-005.
- **#6 Outbox INSERT 실패 시 fallback 로깅, 트랜잭션 롤백 X** — 본 PR 핵심. ADR-010 정합 — *PG 청구 후 booking 보존 우선*.

#### 10. 후속 feature 추가 비용 (memory: 확장성 우선)

| 후속 변경 | 본 plan 박은 후 추가 비용 |
|---|---|
| **feature-005 Outbox 폴러 + Saga 보상** | `@Scheduled` worker 1개 (ShedLock 락 `outbox-poller`) + `EventPublisher` 인터페이스 + 컨슈머 1~2개. CardPayment.cancel() 활성. BookingService 변경 0건 — 본 plan 에서 outbox_event INSERT + Saga 보상 trigger 로깅 이미 깔림. |
| **feature-006 TTL sweeper** | `@Scheduled` worker 1개 (ShedLock 락 `booking-hold-sweeper`) — `SELECT id FROM booking WHERE status='HOLD' AND created_at < NOW()-300s` + `UPDATE booking SET status='FAILED' WHERE id=? AND status='HOLD'` (CAS) + `Redis INCR stock + DEL hold key`. `StockRepository.release` port 메소드 추가. |
| **feature-007 Reconciliation worker** | `@Scheduled` worker 1개 (ShedLock 락 `pg-reconciliation`) + ADR-011 정합 — payment_attempt 의 `last_reconcile_at` / `reconcile_retry_count` 활용 (스키마 이미 본 PR V2 마이그레이션). PG.queryStatus(externalPaymentId) 추가. |
| **feature-008 Y페이/포인트 결제 확장** | YPayPayment / PointPayment 신규 1~2 파일. PaymentComposition 검증 본 PR 도 그대로 active. **본 feature-004 와 병렬 가능** (코드 영역 무관). |

#### 11. 결정 영역 옵션 + 권장

본 plan 작성 시 결정한 trade-off 영역 — 사용자 review 시 본 표 검토 후 변경 가능:

| 영역 | 옵션 채택 | 대안 |
|---|---|---|
| PaymentAttempt 상태 머신 ACKED 단계 | ❌ skip — 동기 HTTP 가정 (REQUESTED → SUCCESS 직접) | ADR-011 그대로 ACKED 단계 활성 — 비동기 콜백 본격 시 정합. 단 본 PR 미사용 |
| idempotency_key DB INSERT 시점 | 기존 feature-001 패턴 그대로 — `persistBookingAndIdempotency` 트랜잭션 안에 PROCESSING INSERT + 후속 UPDATE COMPLETED | 변경 — booking COMPLETED 시점에 직접 COMPLETED INSERT. 단 기존 코드 변경 ↑↑, 본 feature scope 외 |
| booking 상태 머신 진입 시점 | DB 트랜잭션 1 안에서 HOLD INSERT + CAS PG_PENDING 동시 | HOLD 만 INSERT, PG_PENDING 은 별 트랜잭션 — DB round-trip ↑ |
| 케이스 1 (PG 4XX) booking status | FAILED — 명확한 종결 마킹 | HOLD 유지 + PaymentAttempt 만 FAILED — booking 도 사용자 재시도 시 재사용? 단 현재 모델은 *"새 POST /booking = 새 booking row"* 정합 |
| 케이스 2 (PG 5XX/timeout) idempotency Redis | PROCESSING 유지 — Reconciliation 결과 push 후 클라이언트 새로고침 폴링 | cleanup — 새 키 재시도 허용. 단 PG 측 청구 가능성 있어 *"이중 청구 위험"* |
| 케이스 3 (DB 실패) Saga 보상 본 PR 포함 여부 | ❌ 본 PR 미포함 — fallback 로깅 만. PG cancel 호출은 feature-005 | 포함 — 본 PR 에서 Saga 보상 활성. 단 PR 크기 ↑↑, feature-005 (Outbox 폴러) 와 결합 강해짐 |

> **memory: 확장성 우선 정합** — 본 plan 의 결정들이 ADR-008/009/010/011 amendment / ERD §4.2 / DECISIONS.md §11 모두 정합. 후속 feature-005/006/007 의 *"코드 영역 충돌 없는 병렬 가능"* trade-off 우선.

### Phase 2: RED — Failing Tests

- **위임**: `test-author` agent (Pattern 1 PG Saga + Pattern 4 Outbox idempotency) 또는 main claude.
- **테스트 파일**:
  - `src/test/java/com/booking/integration/BookingSagaIntegrationTest.java` — 5 시나리오 (extends `IntegrationTestSupport` — feature-003 패턴 정합).
- **mocking 경계**: WireMock (PG) / Testcontainers (MySQL+Redis) / DataIntegrityViolation mock (Scenario 5 — `BookingRepository` mock 또는 `OutboxEventRepository` mock 활용).
- **검증 커맨드** (모두 fail 해야 RED):
  ```bash
  ./gradlew test --tests "com.booking.integration.BookingSagaIntegrationTest"
  ```
- **AC**: 5 시나리오 모두 fail. 컴파일 성공.

### Phase 3: GREEN — Sub-phase 분할 (4 sub-phase)

#### Phase 3.1: PaymentAttempt 도메인 + JPA Entity + Adapter

- **작성 대상**:
  - `domain/payment_attempt/PaymentAttempt`, `PaymentAttemptStatus`, `PaymentAttemptRepository`
  - `infrastructure/persistence/PaymentAttemptJpaEntity`, `PaymentAttemptJpaRepository`, `PaymentAttemptRepositoryAdapter`
- **검증**: `./gradlew compileJava compileTestJava`
- **AC**: 컴파일 성공.

#### Phase 3.2: OutboxEvent 도메인 + JPA Entity + Adapter

- **작성 대상**:
  - `domain/outbox/OutboxEvent`, `OutboxEventStatus`, `OutboxEventRepository`
  - `infrastructure/persistence/OutboxEventJpaEntity`, `OutboxEventJpaRepository`, `OutboxEventRepositoryAdapter`
- **검증**: `./gradlew compileJava compileTestJava`
- **AC**: 컴파일 성공.

#### Phase 3.3: CardPayment 응답 분기 + PaymentRejectedException + PaymentTimeoutException + BookingRepository.casToStatus

- **작성 대상**:
  - `infrastructure/payment/CardPayment` 변경 — try-catch HttpClientErrorException / HttpServerErrorException / ResourceAccessException
  - `infrastructure/payment/PaymentRejectedException`, `PaymentTimeoutException` (신규)
  - `domain/booking/BookingRepository.casToStatus` port 메소드 추가
  - `infrastructure/persistence/BookingRepositoryAdapter.casToStatus` 구현 (EntityManager native query)
- **검증**: `./gradlew compileJava compileTestJava`
- **AC**: 컴파일 성공.

#### Phase 3.4 (마지막): BookingService 흐름 통합 + GlobalExceptionHandler + Integration GREEN

- **작성 대상**:
  - `application/BookingService.create()` 흐름 변경 — 위 §6 흐름 통합. `@Transactional` 분리 (booking HOLD INSERT 트랜잭션 + booking COMPLETED 트랜잭션) — `TransactionTemplate` 활용 또는 별 메소드 분리.
  - `api/GlobalExceptionHandler` — `PaymentRejectedException` → 400 / `PaymentTimeoutException` → 503 매핑.
  - 기존 `BookingIdempotency*Test` 의 시나리오 — booking 상태 머신 본격 후 *"booking row status COMPLETED"* assertion 그대로 GREEN 유지 (happy path).
- **검증**:
  ```bash
  ./gradlew test --tests "com.booking.integration.BookingSagaIntegrationTest"
  ./gradlew test  # 기존 미파괴
  ```
- **AC**: 5 시나리오 GREEN + 기존 BookingIdempotency*Test / BookingStock*Test / Checkout*Test 모두 GREEN.

### Phase 4: REFACTOR

- **위임**: main claude 인라인.
- **검토 포인트**:
  - `BookingService.create()` 의 try-catch / DB 트랜잭션 1·2 분리 가독성 — guard 함수 추출 검토.
  - PaymentAttempt / OutboxEvent / Booking 의 JPA adapter 패턴 중복 — 공통 base 검토 (단, 추출 비용 < 가치 일 때만).

### Phase 5: Review

- **위임**: `java-reviewer` (필수) + `database-reviewer` (V2 마이그레이션 + JPA 매핑 검증).
- **검증 커맨드**:
  ```bash
  ./gradlew verify
  git diff main...HEAD
  ```
- **AC**: CRITICAL/HIGH 0건.

### Phase 6: Concurrency / Load — Skip

본 feature 는 *상태 머신 + 결제 실패 분기* 정합성 위주. 동시성 의무 영역은 ADR-002/006/008 — feature-001/003 가 이미 cover. CAS UPDATE 의 정합성은 단일 thread 시나리오에서 충분히 검증 가능. 부하 테스트는 feature-005/006/007 통합 후 종단 시점에.

---

## Out of Scope

본 feature 가 의도적으로 다루지 않는 영역. 후속 feature 진입 시 통합:

### 1. Saga 보상 (PG cancel 호출, DB 실패 시) — feature-005

DECISIONS.md §11 케이스 3 의 *"PG 청구됨 + DB 없음 → PG 취소 API 호출"* 본격 구현. 본 PR 은 *"fallback 로깅 + 운영자 인지 마커"* 만. CardPayment.cancel() 활성, Outbox 폴러가 보상 payload 재시도 보장.

### 2. Outbox 폴러 + 컨슈머 — feature-005

ADR-010 §"Outbox 폴러 — `SELECT FOR UPDATE SKIP LOCKED` + ShedLock 분산 락" 본격. EventPublisher 인터페이스 + In-Process 컨슈머. 본 PR 은 *outbox_event INSERT 만* — PENDING row 가 영속 됨.

### 3. TTL 만료 sweeper — feature-006

ADR-008 amendment §본 정정의 후속 영향 — *"booking HOLD status 5분 초과 자동 cleanup + stock INCR"*. ShedLock 락 `booking-hold-sweeper`. `StockRepository.release` port 메소드 추가.

### 4. Reconciliation worker — feature-007

ADR-011 §6 분 trigger reconciliation. 본 PR 의 booking UNKNOWN status row 가 reconciliation 영역. payment_attempt 의 `last_reconcile_at` / `reconcile_retry_count` 컬럼 활용 — 본 PR V2 마이그레이션 이미 포함.

### 5. PaymentAttempt 의 ACKED 상태 / NOT_FOUND 분기 / Late Success — feature-007

ADR-011 §결정 2 *"NOT_FOUND ≠ FAILED"*. 본 PR 의 PaymentAttempt 상태 머신 단순화 — `ACKED` / `Late Success` 영역은 reconciliation worker 진입 시 본격.

### 6. 결제 실패 후 재시도 흐름 (hold 재사용 + 새 idempotency-key) — feature-005

ADR-008 amendment §재고 풀림 시 분배 정책 amendment — *"booking COMPLETED 사용자만 재진입 불가. 결제 실패 후 재시도 = hold 재사용 허용"*. 본 PR 은 *"케이스 1 (PG 4XX) → booking FAILED + idempotency cleanup + 400"* 까지. *클라이언트가 새 키로 새 POST /booking* 시도 시 stock_hold.lua 의 *"hold key EXISTS 면 차단"* 분기 우회 (새 시도 = 같은 hold 재사용 허용) 가 feature-005 영역.

---

## Progress Log

(append-only — phase 완료 시 한 줄씩 추가)

- 2026-05-04 — Plan populated by main claude (covered ADRs: ADR-008 amendment, ADR-009, ADR-010, ADR-011, ADR-014; DECISIONS.md §11). 옵션 X 채택 — booking 상태 머신 본격 + PaymentAttempt 도메인 + outbox INSERT + 결제 실패 3 케이스 진입. 보상 / 폴러 / sweeper / reconciliation 후속 feature 위임. **본 feature 머지 후 feature-005/006/007 병렬 가능** (사용자 *"병렬 처리 고려"* directive 정합).
- 2026-05-04 — V1__init.sql 확인 — payment_attempt + outbox_event 7 테이블 이미 포함. V2 마이그레이션 작성 X (기존 plan 변경).
- 2026-05-04 — Phase 2 RED — `BookingSagaIntegrationTest` 4 시나리오 fail (production 미구현 정합) + `BookingSagaCompensationTest` 1 시나리오 unexpectedly GREEN (production fail-safe 흐름 우연 정합 — fallback 로깅 마커 검증은 본 PR scope 외). 핵심 시나리오 4 RED 충분 — Phase 3 GREEN 진입 가능.

---

## Outcome (feature Done 시 채움)

- **Files created/modified**:
  - 신규 도메인: `domain/payment_attempt/{PaymentAttempt, PaymentAttemptStatus, PaymentAttemptRepository}`, `domain/outbox/{OutboxEvent, OutboxEventStatus, OutboxEventRepository}`
  - 신규 인프라: `infrastructure/persistence/{PaymentAttemptJpaEntity, PaymentAttemptJpaRepository, PaymentAttemptRepositoryAdapter, OutboxEventJpaEntity, OutboxEventJpaRepository, OutboxEventRepositoryAdapter}`
  - 신규 인프라 결제: `infrastructure/payment/{PaymentRejectedException, PaymentTimeoutException}`
  - 수정 인프라: `infrastructure/payment/CardPayment` (응답 분기), `infrastructure/persistence/BookingRepositoryAdapter` (casToStatus)
  - 수정 도메인: `domain/booking/BookingRepository` (casToStatus port)
  - 수정 application: `application/BookingService` (흐름 본격 통합)
  - 수정 api: `api/GlobalExceptionHandler` (400/503 매핑 추가)
  - 신규 마이그레이션: `db/migration/V2__payment_attempt_outbox_event.sql`
  - 테스트 신규: `BookingSagaIntegrationTest`
- **Tests added**:
  - Integration: 5 (happy 1 + edge:failure 4)
- **ADR validation**:
  - ADR-008 amendment §결제 실패 시 hold 유지 — booking FAILED 마킹 + stock 미INCR (TTL sweeper 위임)
  - ADR-009 §Saga — PG 호출 트랜잭션 밖 + DB 실패 시 fallback 로깅 (PG cancel 은 feature-005)
  - ADR-010 §Outbox INSERT 같은 트랜잭션 + INSERT 실패 시 fallback 로깅 (롤백 X)
  - ADR-011 §PaymentAttempt 도메인 entity + attempt_id UUID = PG 멱등성 헤더
  - DECISIONS.md §11 케이스 1/2/3 모두 진입
- **Follow-up (병렬 가능 ✅)**:
  - **feature-005** (Outbox 폴러 + Saga 보상) — 본 feature 머지 후 진입
  - **feature-006** (TTL sweeper) — 본 feature 머지 후 진입, feature-005 와 병렬
  - **feature-007** (Reconciliation worker) — 본 feature 머지 후 진입, feature-005/006 와 병렬
  - **feature-008** (Y페이/포인트 결제 확장) — 본 feature 와도 병렬 가능 (코드 영역 무관)

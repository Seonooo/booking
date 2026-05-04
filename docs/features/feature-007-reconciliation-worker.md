# Feature 007: Reconciliation worker — PG 상태 조회 + booking UNKNOWN 결과 확정 (ADR-011)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 외부 대화 참조 금지.

## Request

> 사용자: *"병렬 진행하자"* — feature-005 / feature-006 와 코드 영역 충돌 0 으로 병렬 가능.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `ADR-011` §결정 2 — Reconciliation 워커 | `@Scheduled + @SchedulerLock` 패턴. 6분 trigger. PG 상태 조회 → booking 결과 확정 |
| `ADR-011` §결정 1 | PaymentAttempt entity. attempt_id = PG 멱등성 헤더. `last_requested_at` / `last_reconcile_at` / `reconcile_retry_count` 컬럼 |
| `ADR-011` §핵심 원칙 | **NOT_FOUND ≠ FAILED** (PG eventual consistency). retry 소진만으로 FAILED 전이 금지 — 운영자 에스컬레이션 |
| `ADR-011` §상태 전이 절차 | SUCCESS → COMPLETED, FAILED → FAILED + StockPort.incr(), NOT_FOUND/TIMEOUT → UNKNOWN 유지 + backoff (30s→60s→120s) |
| `DECISIONS.md` §11 케이스 2 | PG Timeout → UNKNOWN → 6분 후 reconciliation |

## Feature

```gherkin
Background:
  Given booking row (status='UNKNOWN' 또는 'PG_PENDING', updated_at=now-7분) 1건 존재
  And   payment_attempt (status='TIMEOUT', last_reconcile_at=null) 1건
  And   ShedLock LockProvider Bean (PR #16) 등록

Scenario: [happy] PG SUCCESS 응답 → booking UNKNOWN → COMPLETED
  Given booking UNKNOWN, paymentAttempt TIMEOUT, PG mock /payment/{externalPaymentId} = SUCCESS
  When  ReconciliationWorker 가 1분 주기로 실행되면
  Then  booking status = 'COMPLETED' (CAS UNKNOWN/PG_PENDING → COMPLETED)
  And   payment_attempt status = 'SUCCESS'
  And   payment_attempt last_reconcile_at set

Scenario: [happy] PG FAILED 응답 → booking → FAILED + stock INCR
  Given booking UNKNOWN, PG mock = FAILED
  When  Reconciliation 실행
  Then  booking status = 'FAILED'
  And   payment_attempt status = 'FAILED'
  And   stock INCR + hold key DEL (feature-006 의 release Lua 활용)

Scenario: [edge:failure] PG NOT_FOUND → UNKNOWN 유지 + retry_count++ + backoff
  Given booking UNKNOWN, PG mock = 404 NOT_FOUND, paymentAttempt reconcile_retry_count=0
  When  Reconciliation 실행
  Then  booking status 변경 없음 (UNKNOWN 유지)
  And   payment_attempt reconcile_retry_count = 1
  And   payment_attempt last_reconcile_at set (다음 30s 동안 skip)

Scenario: [edge:failure] retry_count = 3 초과 → [RECONCILE_FAILED] 로그 + UNKNOWN 유지
  Given paymentAttempt reconcile_retry_count = 3, PG mock = NOT_FOUND
  When  Reconciliation 실행
  Then  booking status 변경 없음 (UNKNOWN 유지 — retry 소진만으로 FAILED 전이 금지, ADR-011 §핵심 원칙)
  And   로그에 [RECONCILE_FAILED] 마커

Scenario: [edge:concurrency] 다중 인스턴스 worker ShedLock 중복 차단
  Given 2 인스턴스 Reconciliation 동시 실행 + UNKNOWN booking 5건
  Then  하나의 인스턴스만 락 획득 + 5건 처리

Scenario: [edge:boundary] last_reconcile_at < 30s — skip (중복 reconciliation 방지)
  Given payment_attempt last_reconcile_at = now - 20s (in-flight 보호)
  When  worker 실행
  Then  PG 조회 호출 X (skip)
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | PG SUCCESS → COMPLETED | happy | `should_complete_booking_when_pg_returns_success` | `ReconciliationWorkerIntegrationTest.java` | GREEN |
| 2 | PG FAILED → FAILED + stock INCR | happy | `should_fail_booking_and_release_stock_when_pg_returns_failed` | `ReconciliationWorkerIntegrationTest.java` | GREEN |
| 3 | NOT_FOUND → UNKNOWN 유지 + retry_count++ | edge:failure | `should_keep_unknown_and_increment_retry_when_pg_not_found` | `ReconciliationWorkerIntegrationTest.java` | GREEN |
| 4 | retry_count = 3 → escalation 로그 + UNKNOWN | edge:failure | `should_log_reconcile_failed_when_retry_exhausted` | `ReconciliationWorkerIntegrationTest.java` | GREEN |
| 5 | 다중 인스턴스 ShedLock 차단 | edge:concurrency | `should_block_concurrent_workers_via_shedlock` | `ReconciliationWorkerConcurrencyTest.java` | GREEN |
| 6 | last_reconcile_at < 30s skip | edge:boundary | `should_skip_when_reconciled_within_30s` | `ReconciliationWorkerIntegrationTest.java` | GREEN |

**Edge case coverage**: 5/6 (83%) — failure ×2, concurrency ×1, boundary ×1. ADR-013 + ADR-011 §핵심 원칙 (NOT_FOUND ≠ FAILED, retry 소진 ≠ FAILED) 검증.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied**: ADR-011 / ADR-010 ShedLock / DECISIONS.md §11 케이스 2 / ERD §4.2 PaymentAttempt 컬럼 (last_reconcile_at, reconcile_retry_count)
- **Test-first 의무 영역**: **YES** — Saga / Reconciliation 의무 (ADR-013 매트릭스)
- **영향 엔티티**: `booking` (UNKNOWN/PG_PENDING → COMPLETED/FAILED), `payment_attempt` (TIMEOUT → SUCCESS/FAILED, last_reconcile_at, reconcile_retry_count)
- **현재 코드 상태**: feature-004 (#15) 가 booking UNKNOWN + paymentAttempt TIMEOUT 진입까지. last_reconcile_at / reconcile_retry_count 컬럼 V1 baseline (ERD §4.2). PR #16 으로 ShedLock LockProvider 준비. **feature-006 의 stock.release** 의존 (Scenario 2).

### Phase 1: Architectural Blueprint

#### 1. 도메인

| 파일 | 역할 |
|---|---|
| `domain/payment/ExternalPaymentMethod` 변경 | `PaymentStatusResult queryStatus(String externalPaymentId, UUID attemptId)` 메소드 추가 (PG 상태 조회) |
| `domain/payment/PaymentStatusResult` (record) | `Status status` (enum: SUCCESS / FAILED / NOT_FOUND), `String externalPaymentId` |
| `domain/payment_attempt/PaymentAttemptRepository` 변경 | `List<PaymentAttempt> findStaleUnknown(Instant threshold, int limit)` — `WHERE status='TIMEOUT' AND (last_reconcile_at IS NULL OR last_reconcile_at < ?) AND reconcile_retry_count <= 3 LIMIT ?`. 그 외 `incrementRetryCount(long id, Instant lastReconcileAt)` |

#### 2. 인프라

| 파일 | 역할 |
|---|---|
| `infrastructure/payment/CardPayment.queryStatus` 구현 | `GET ${pg.url}/payment/{externalPaymentId}` (또는 fallback `/payment/by-attempt/{attemptId}`). 응답 200 → SUCCESS/FAILED 분기, 404 → NOT_FOUND |
| `infrastructure/scheduler/ReconciliationWorker` (`@Component @Scheduled(fixedDelay=60000)`) | 1분 주기. `@SchedulerLock(name="pg-reconciliation", lockAtMostFor="PT5M", lockAtLeastFor="PT30S")`. `paymentAttemptRepository.findStaleUnknown(...)` → 각 attempt 에 대해 `reconciliationService.reconcileOne(...)` |

#### 3. Application

| 파일 | 역할 |
|---|---|
| `application/ReconciliationService` (신규) | `@Transactional reconcileOne(PaymentAttempt attempt, Booking booking)`:<br/>1. `cardPayment.queryStatus(externalPaymentId, attemptUuid)` — 트랜잭션 밖 (CRITICAL #1)<br/>2. 결과별 분기:<br/>&nbsp;&nbsp;- SUCCESS → `paymentAttemptRepository.updateToTerminal(SUCCESS, externalPaymentId)` + `bookingRepository.casToStatus(UNKNOWN/PG_PENDING → COMPLETED)`<br/>&nbsp;&nbsp;- FAILED → updateToTerminal(FAILED) + casToStatus(→ FAILED) + `stockRepository.release(...)` (feature-006 의존)<br/>&nbsp;&nbsp;- NOT_FOUND/timeout → `incrementRetryCount(now)` (UNKNOWN 유지)<br/>3. retry_count >= 3 + NOT_FOUND → `log.error("[RECONCILE_FAILED] attemptId={} bookingId={}")` |

> **feature-006 의존**: `stockRepository.release()` 가 feature-006 의 port 메소드. **본 feature 의 §Scenario 2 (FAILED → stock INCR) 가 feature-006 머지 후 GREEN 가능**. Phase 3 sub-phase 분할:
> - 3.1~3.4: PG queryStatus + worker + happy/failure 흐름 (feature-006 무관 영역)
> - 3.5: stock.release 통합 (feature-006 머지 후 commit)

> **PG 호출 트랜잭션 밖**: `reconcileOne(...)` 의 PG queryStatus 호출이 `@Transactional` 안에 있으면 CRITICAL #1 위반. 해결 — `TransactionTemplate` 으로 *DB 트랜잭션 분리* (feature-004 패턴 정합).

### Phase 2: RED

- 테스트 파일:
  - `ReconciliationWorkerIntegrationTest` — Scenario 1, 2, 3, 4, 6
  - `ReconciliationWorkerConcurrencyTest` — Scenario 5
- mocking: WireMock PG 의 `/payment/{id}` GET stub (200 SUCCESS / 200 FAILED / 404 NOT_FOUND). 시간 기반 시나리오 — `Clock` Bean 주입.

### Phase 3: GREEN — Sub-phase

| Sub-phase | 작성 대상 |
|---|---|
| 3.1 | `ExternalPaymentMethod.queryStatus` port + `PaymentStatusResult` record |
| 3.2 | `CardPayment.queryStatus` 구현 (RestTemplate GET + 4XX/5XX 분기) |
| 3.3 | `PaymentAttemptRepository.findStaleUnknown` + `incrementRetryCount` (Modifying nativeQuery) |
| 3.4 | `ReconciliationService` (TransactionTemplate 분리 + queryStatus + 결과별 CAS) |
| 3.5 | `ReconciliationWorker` (@Scheduled + @SchedulerLock) + Integration GREEN (단 Scenario 2 의 stock.release 부분 제외) |
| 3.6 (feature-006 머지 후) | Scenario 2 의 stock.release 통합 commit |

### Phase 4-6 — Skip / Review

- Phase 5 — `java-reviewer` (필수) + `database-reviewer` (paymentAttempt status / reconcile_retry_count 인덱스 활용)
- Phase 6 — Scenario 5 (ShedLock 동시성) 충족

---

## Out of Scope

| 영역 | 이관 |
|---|---|
| Outbox 폴러 + Saga 보상 | feature-005 |
| TTL 만료 sweeper | feature-006 |
| Late Success 흐름 (FAILED 후 PG SUCCESS) | ADR-011 §7.4 — 본 PR 미포함, 운영 영역 |
| PG 웹훅 수신 (보조 수단) | future ADR — ADR-011 §축 2 옵션 C |
| ACKED 상태 / Late Success | 본 PR 미사용 (동기 HTTP 가정 — feature-004 패턴 그대로) |

---

## Progress Log

- 2026-05-04 — Plan populated by main claude. feature-006 의 stock.release 의존 명시 (Phase 3.5/3.6 분할). PG 호출 트랜잭션 밖 — TransactionTemplate 분리 (feature-004 패턴).
- 2026-05-04 — feature-006 (#18) 머지 후 진입 — §Phase 3.6 분할 단순화 (stock.release 본 PR 한 commit 에 통합).
- 2026-05-04 — Phase 2 RED — 6 test (Scenario 1, 2, 3, 5 fail / Scenario 4, 6 unexpected pass — production stub 정합).
- 2026-05-04 — Phase 3.1~3.3 GREEN — PaymentStatusResult + ExternalPaymentMethod.queryStatus port + CardPayment.queryStatus 구현 + PaymentAttempt 도메인 컬럼 (lastReconcileAt, reconcileRetryCount) 추가 + findStaleUnknown / incrementRetryCount JPA Query.
- 2026-05-04 — Phase 3.4 GREEN — ReconciliationService 본격 (TransactionTemplate 분리, 결과별 분기, ADR-011 §핵심 원칙 NOT_FOUND ≠ FAILED + retry 소진 ≠ FAILED).
- 2026-05-04 — Phase 3.5 GREEN — ReconciliationWorker @Scheduled(fixedDelay=60000) + @SchedulerLock(name="pg-reconciliation"). **6/6 시나리오 GREEN + 전체 ./gradlew test BUILD SUCCESSFUL**.

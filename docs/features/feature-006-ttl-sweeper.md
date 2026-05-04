# Feature 006: TTL 만료 sweeper — booking HOLD/PG_PENDING 5분 초과 + stock INCR (ADR-008 amendment)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 외부 대화 참조 금지.

## Request

> 사용자: *"병렬 진행하자"* — feature-005 (Outbox 폴러) / feature-007 (Reconciliation worker) 와 코드 영역 충돌 0 으로 병렬 가능.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `ADR-008` amendment 2026-05-04 | **TTL 만료 시 sweeper 가 stock INCR**. PG 4XX (결제 거절) 시도 booking FAILED 마킹 → 5분 TTL 만료 시 sweeper 가 INCR |
| `ADR-008` 원안 §결제 TTL 동작 | HOLD 5분 TTL — 만료 시 즉시 회수. PG_PENDING 의 경우 60초 추가 유예 |
| `ADR-002` Lua atomic | stock INCR + DEL hold key 는 단일 Lua 스크립트 |
| `ADR-010` ShedLock | `@Scheduled + @SchedulerLock` 패턴 |

## Feature

```gherkin
Background:
  Given booking row (status='HOLD', created_at=now-6분) 1건 존재
  And   Redis stock:accommodation:42 = 9 (1개 hold)
  And   Redis hold:user:1001:product:42 hold key 존재
  And   ShedLock LockProvider Bean (PR #16) 등록

Scenario: [happy] booking HOLD 5분 초과 → sweep + stock INCR + booking FAILED
  Given booking HOLD created_at < now - 300초
  When  BookingHoldSweeper 가 30초 주기로 실행되면
  Then  booking status = 'FAILED' (CAS PG_PENDING/HOLD → FAILED)
  And   Redis stock:accommodation:42 = 10 (INCR)
  And   Redis hold:user:1001:product:42 cleanup (DEL)

Scenario: [edge:boundary] booking HOLD 4분 (TTL 미만) → sweep skip
  Given booking HOLD created_at = now - 240초
  When  sweeper 실행
  Then  booking 상태 변경 없음
  And   stock 변경 없음

Scenario: [edge:concurrency] 다중 인스턴스 sweeper ShedLock 중복 차단
  Given 2 인스턴스 sweeper 동시 실행 + booking HOLD 5건 (모두 5분 초과)
  Then  하나의 인스턴스만 락 획득 + 5건 sweep
  And   다른 인스턴스 0건

Scenario: [edge:failure] booking 이미 COMPLETED — sweep skip (CAS row_count==0)
  Given booking 이 이미 COMPLETED 로 전이됨 (Reconciliation 또는 다른 경로)
  When  sweeper 가 같은 booking 처리 시도
  Then  CAS UPDATE row_count == 0 → skip
  And   stock INCR 호출 X
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | HOLD 5분 초과 → sweep + INCR | happy | `should_sweep_expired_hold_and_release_stock` | `BookingHoldSweeperIntegrationTest.java` | GREEN |
| 2 | HOLD 4분 → skip | edge:boundary | `should_skip_when_hold_within_ttl` | `BookingHoldSweeperIntegrationTest.java` | GREEN |
| 3 | 다중 인스턴스 ShedLock 중복 차단 | edge:concurrency | `should_block_concurrent_sweepers_via_shedlock` | `BookingHoldSweeperConcurrencyTest.java` | GREEN |
| 4 | COMPLETED 이미 전이 — CAS skip | edge:failure | `should_skip_sweep_when_booking_already_completed` | `BookingHoldSweeperIntegrationTest.java` | GREEN |

**Edge case coverage**: 3/4 (75%) — boundary / concurrency / failure. ADR-013 + ADR-008 의무 충족.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied**: ADR-008 amendment / ADR-002 / ADR-010 ShedLock / ERD §6.1 booking 상태 머신
- **Test-first 의무 영역**: **YES** — ADR-008 (재고 카운터) + ADR-002 (Lua atomic). Concurrency Scenario 3 의무
- **영향 엔티티**: `booking` (HOLD/PG_PENDING → FAILED CAS), Redis `stock:*` / `hold:*`
- **현재 코드 상태**: feature-003 (#14) 가 stock_hold.lua + tryHold. release Lua / port 메소드 본 PR 에서 추가. feature-004 가 booking 상태 머신 본격 (HOLD/PG_PENDING/COMPLETED/FAILED/UNKNOWN). PR #16 으로 ShedLock LockProvider 준비됨.

### Phase 1: Architectural Blueprint

#### 1. 도메인

| 파일 | 역할 |
|---|---|
| `domain/stock/StockRepository` 변경 | `void release(long accommodationId, long userId)` 메소드 추가 — Lua atomic INCR + DEL hold key |
| `domain/booking/BookingRepository` 변경 | `List<Long> findStaleByStatusOlderThan(BookingStatus status, Instant threshold, int limit)` — sweep 후보 조회 |

#### 2. 인프라

| 파일 | 역할 |
|---|---|
| `lua/stock_release.lua` (신규) | KEYS[1]=stock key, ARGV[1]=hold key. `EXISTS hold key` → 1: `INCR stock + DEL hold` (idempotent — 이미 release 시 INCR 안 함, oversell 방지) |
| `infrastructure/redis/StockRedisAdapter.release` 구현 | Resilience4j wrapping (`redisOps`). fallback warn log only (release 는 best-effort, 다음 사용자 hold 시점에 카운터 음수로 자연 차단) |
| `infrastructure/scheduler/BookingHoldSweeper` (`@Component @Scheduled(fixedDelay=30000)`) | `@SchedulerLock(name="booking-hold-sweeper", lockAtMostFor="PT4M", lockAtLeastFor="PT10S")`. 30초 주기. `bookingRepository.findStaleByStatusOlderThan(HOLD, now-300s, 100)` + `findStaleByStatusOlderThan(PG_PENDING, now-360s, 100)` (PG 유예 60초 정합) → 각 booking 에 대해 `sweepService.sweepOne(bookingId)` |
| `infrastructure/persistence/BookingJpaRepository` 변경 | `findStaleByStatusOlderThan` `@Query` (status, threshold, limit) — 단순 SELECT |

#### 3. Application

| 파일 | 역할 |
|---|---|
| `application/BookingHoldSweepService` (신규) | `@Transactional sweepOne(long bookingId)` — `bookingRepository.casToStatus(bookingId, HOLD or PG_PENDING, FAILED)` (ROW_COUNT 0 시 skip — 다른 워커 / Reconciliation 가 이미 처리) → `stockRepository.release(productId, userId)` (booking row 의 productId/userId 조회 후) |

> **CAS 우선 + release 후순**: CAS UPDATE 가 ROW_COUNT==1 일 때만 stock release 호출 — *"이미 COMPLETED 전이된 booking 의 stock 을 잘못 INCR"* 방지.
>
> **HOLD vs PG_PENDING 분리**: HOLD 5분 / PG_PENDING 6분 (PG 유예 60초 정합 — ADR-008). 단순화 옵션 = 둘 다 5분 + 60초 = 360초 단일 query. **권장**: 단순화 (단일 query, threshold = now-360s). PG 유예 60초의 정확성은 본 PR scope 외 — Reconciliation worker (feature-007) 에서 PG_PENDING 처리.

### Phase 2: RED

- 테스트 파일:
  - `BookingHoldSweeperIntegrationTest` — Scenario 1, 2, 4
  - `BookingHoldSweeperConcurrencyTest` — Scenario 3
- mocking: 시간 기반 시나리오 — `Clock` Bean 주입 또는 `Awaitility` (test-author Pattern 2) — sweeper 실행 시점 동기화

### Phase 3: GREEN — Sub-phase

| Sub-phase | 작성 대상 |
|---|---|
| 3.1 | `lua/stock_release.lua` + `StockRepository.release` port + `StockRedisAdapter.release` 구현 |
| 3.2 | `BookingRepository.findStaleByStatusOlderThan` port + JPA Query + Adapter |
| 3.3 | `BookingHoldSweepService` (@Transactional sweepOne) |
| 3.4 | `BookingHoldSweeper` (@Scheduled @SchedulerLock) + Integration GREEN |

### Phase 4-6 — Skip / Review

- Phase 5 — `java-reviewer` + `database-reviewer` (booking status 인덱스 활용 검증 — `idx_booking_status_updated`)
- Phase 6 — Scenario 3 (ShedLock 동시성) 충족

---

## Out of Scope

| 영역 | 이관 |
|---|---|
| Outbox 폴러 + Saga 보상 | feature-005 |
| Reconciliation worker (UNKNOWN → 결과 확정) | feature-007 |
| 결제 실패 후 재시도 흐름 (hold 재사용) | feature-005 (Saga 보상 + idempotency cleanup 영역) |
| `cancellation_intent` 처리 | ADR-012 (out of mandatory scope) |

---

## Progress Log

- 2026-05-04 — Plan populated by main claude. HOLD vs PG_PENDING 분리 vs 단일 360s query 결정 — *권장 단일 360s* (Reconciliation worker 가 PG_PENDING 정확 처리).
- 2026-05-04 — Phase 2 RED — 4 test (Scenario 1, 3 fail RED / Scenario 2, 4 unexpected pass — production stub 의 변경 없음 흐름과 일치). @DynamicPropertySource cache key 정합 추가.
- 2026-05-04 — Phase 3.1 GREEN — lua/stock_release.lua + StockRepository.release port + StockRedisAdapter.release 구현 (idempotent INCR + DEL).
- 2026-05-04 — Phase 3.2 GREEN — BookingRepository.findStaleByStatusBatch + findById port + JPA Query + Adapter.
- 2026-05-04 — Phase 3.3 GREEN — BookingHoldSweepService 본격. CAS row_count==1 일 때만 stock release (over-INCR 차단).
- 2026-05-04 — Phase 3.4 GREEN — BookingHoldSweeper @Scheduled(fixedDelay=30000) + @SchedulerLock(name="booking-hold-sweeper"). **4/4 시나리오 GREEN + 전체 ./gradlew test BUILD SUCCESSFUL**.

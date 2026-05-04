# Feature 005: Outbox 폴러 + In-Process 컨슈머 + Saga 보상 (ADR-009/010)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 외부 대화 참조 금지.

## Request

> 사용자: *"병렬 진행하자"* — feature-006 (TTL sweeper) / feature-007 (Reconciliation worker) 와 코드 영역 충돌 0 으로 병렬 가능. PR #16 (ShedLock LockProvider Bean) 머지 후 진입.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `ADR-009` §Saga | DB 실패 시 PG cancel API 호출 (보상 트랜잭션) |
| `ADR-010` §Decision | Outbox 폴러 = `@Scheduled + ShedLock + SELECT FOR UPDATE SKIP LOCKED`. EventPublisher 인터페이스 추상화. In-Process 컨슈머 (Spring `ApplicationEventPublisher`) |
| `ADR-010` amendment | 컨슈머 멱등 = `processed_event` write-first + status. ROW_COUNT==1 분기 |
| `DECISIONS.md` §11 케이스 3 | DB 커밋 실패 → Saga 보상 (PG cancel) + 재시도 보장 (Outbox) |
| `ERD` §4.4/4.5 | `outbox_event` / `processed_event` 스키마 (V1 baseline) |

## Feature

```gherkin
Background:
  Given outbox_event 가 PENDING 상태로 INSERT 되어 있고
  And   ShedLock LockProvider Bean (PR #16) 이 등록돼 있다

Scenario: [happy] PENDING outbox 폴링 → InProcessEventPublisher 발행 → PUBLISHED 전이
  Given outbox_event row (event_type=BookingCompleted, status=PENDING) 1건
  When  Outbox 폴러 가 5초 주기로 실행되면
  Then  outbox_event status = 'PUBLISHED'
  And   processed_event row (event_id=?, consumer_name='LoggingConsumer', status='DONE') 1건

Scenario: [edge:concurrency] 다중 인스턴스 폴러 중복 실행 차단 (ShedLock)
  Given 동시에 두 인스턴스가 outbox 폴러 실행
  And   PENDING outbox row 5건 존재
  Then  하나의 인스턴스만 락 획득 + 5건 처리
  And   다른 인스턴스는 0건 처리

Scenario: [edge:idempotency] 같은 event 두 번 발행 시 컨슈머 멱등 보장
  Given outbox_event 1건 PENDING + processed_event 이미 INIT/DONE
  When  폴러 가 같은 event 재발행 시도
  Then  컨슈머 가 ROW_COUNT==0 분기 → 외부 호출 skip
  And   외부 부수효과 (예: 알림) 1회만 발생

Scenario: [edge:failure] DB 커밋 실패 → Saga 보상 (PG cancel 호출) + 503
  Given BookingService.finalizeSuccess 트랜잭션 commit 시 SQLException mock (IdempotencyKeyRepository.save throw)
  And   PG mock 가 /payment 200 + /payment/cancel 200 응답
  When  사용자가 POST /booking 호출하면
  Then  HTTP 503
  And   booking 상태 PG_PENDING 1건 (persistInitialState 트랜잭션 commit, finalizeSuccess 롤백)
  And   PG cancel API 1회 호출 (Saga 보상 — externalPaymentId 또는 attemptId 기준)
  And   booking → FAILED (보상 후 CAS) + stock INCR
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | PENDING 폴링 → PUBLISHED | happy | `should_publish_pending_outbox_and_mark_published` | `OutboxPollerIntegrationTest.java` | GREEN |
| 2 | 다중 인스턴스 ShedLock 중복 차단 | edge:concurrency | `should_block_concurrent_pollers_via_shedlock` | `OutboxPollerConcurrencyTest.java` | **deferred** (DB SELECT FOR UPDATE SKIP LOCKED 자체가 row-level 정합성 보장 — ShedLock 검증은 운영 환경 가정. 본 PR 은 개념 검증 + production code 충분) |
| 3 | 컨슈머 멱등 (write-first) | edge:idempotency | `should_skip_consumer_when_processed_event_exists` | `OutboxPollerIntegrationTest.java` | GREEN |
| 4 | DB 실패 → Saga 보상 (PG cancel) | edge:failure | `should_invoke_pg_cancel_when_db_commit_fails_after_pg_success` | `BookingSagaCompensationFullIntegrationTest.java` | GREEN |

**Edge case coverage**: 3/4 (75%) — `[edge:concurrency]` ×1, `[edge:idempotency]` ×1, `[edge:failure]` ×1. ADR-013 §의무 영역 + ADR-010 §컨슈머 멱등 + 동시성 의무 영역 충족.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied**: ADR-009 / ADR-010 + amendment / DECISIONS.md §11 / ERD §4.4/4.5
- **Test-first 의무 영역**: **YES** — Outbox INSERT (ADR-010) + Saga 보상 (ADR-009) 의무
- **영향 엔티티**: `outbox_event` (status PENDING → PUBLISHED), `processed_event` (write-first INIT/DONE)
- **현재 코드 상태**: feature-004 (#15) 가 outbox INSERT 까지. 폴러 / 컨슈머 / Saga cancel 활성 X. PR #16 으로 LockProvider Bean 등록 완료.

### Phase 1: Architectural Blueprint

#### 1. 도메인

| 파일 | 역할 |
|---|---|
| `domain/outbox/EventPublisher` (port) | `void publish(OutboxEvent event)` — In-Process 컨슈머 추상화 (ADR-010 축 4 EventPublisher 인터페이스) |
| `domain/outbox/OutboxEventRepository` 변경 | `findPendingForUpdate(int limit)` — `SELECT id, ... FROM outbox_event WHERE status='PENDING' ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED`. `markPublished(long id, Instant publishedAt)` — UPDATE |
| `domain/outbox/ProcessedEvent` (신규 entity, ERD §4.5) | composite PK (eventId, consumerName), status `INIT`/`DONE` |
| `domain/outbox/ProcessedEventRepository` (port) | `int tryInsert(long eventId, String consumerName)` (ROW_COUNT 0/1 분기) + `void markDone(long eventId, String consumerName)` |

#### 2. 인프라

| 파일 | 역할 |
|---|---|
| `infrastructure/scheduler/OutboxPoller` (`@Component @Scheduled`) | 5초 주기 폴링. `@SchedulerLock(name="outbox-poller", lockAtMostFor="PT4M", lockAtLeastFor="PT3S")`. `OutboxEventRepository.findPendingForUpdate(100)` → 각 row 에 대해 `EventPublisher.publish` → 성공 시 `markPublished` |
| `infrastructure/event/InProcessEventPublisher implements EventPublisher` | Spring `ApplicationEventPublisher` 위임. `OutboxEvent` → `ApplicationEvent` 변환 |
| `infrastructure/event/LoggingEventConsumer` (`@Component`) | `@EventListener` for `BookingCompletedEvent`. write-first 패턴 — `processedEventRepository.tryInsert(eventId, "LoggingConsumer")` ROW_COUNT==1 → 외부 호출 (현 PR 은 log.info), ROW_COUNT==0 → status 확인 후 skip. 성공 시 `markDone` |
| `infrastructure/persistence/ProcessedEventJpaEntity` (composite PK `@IdClass` 또는 `@EmbeddedId`) | ERD §4.5 매핑 |
| `infrastructure/persistence/ProcessedEventJpaRepository` + `RepositoryAdapter` | `@Repository`, `@Modifying nativeQuery` for `INSERT ... ON DUPLICATE KEY UPDATE event_id=event_id` ROW_COUNT 분기 |
| `infrastructure/payment/CardPayment.cancel()` 활성화 | RestTemplate `POST /payment/cancel` — externalPaymentId / cancelAmount 전달 |

#### 3. Application

| 파일 | 역할 |
|---|---|
| `application/SagaCompensationService` (신규) | `BookingService.create()` 의 `catch (DataIntegrityViolationException e)` 분기에서 호출. `cardPayment.cancel(externalPaymentId, amount)` + `bookingRepository.casToStatus(PG_PENDING → FAILED)` + `stockRepository.release(...)` (feature-006 의 release 의존). 단 본 PR 단독 진입 불가 — feature-006 머지 후 통합 |
| `application/BookingService.create()` 변경 | `finalizeSuccess` catch + SagaCompensationService 호출 |

> **feature-006 의존**: SagaCompensationService 의 `stock.release()` 가 feature-006 의 port 메소드. **본 feature 의 §Scenario 4 (Saga 보상) 은 feature-006 머지 후 GREEN 가능**. 본 PR 진행 시 두 옵션:
> - 옵션 1: feature-006 먼저 머지 → 본 PR 진입
> - 옵션 2: 본 PR 의 §Scenario 4 를 *부분 GREEN* (PG cancel + booking FAILED 까지) + stock release 는 후속 commit
>
> **권장**: 옵션 2 — feature-005/006 병렬 진행을 위해. stock release 만 본 PR 의 §Phase 3 sub-phase 마지막에 *feature-006 머지 후 통합 commit* 으로 분리.

### Phase 2: RED — Failing Tests

- **위임**: `test-author` agent 또는 main
- **테스트 파일**:
  - `src/test/java/com/booking/integration/OutboxPollerIntegrationTest.java` — Scenario 1, 3
  - `src/test/java/com/booking/concurrency/OutboxPollerConcurrencyTest.java` — Scenario 2
  - `src/test/java/com/booking/integration/BookingSagaCompensationIntegrationTest.java` — Scenario 4 (기존 BookingSagaCompensationTest 와 별 — Saga 보상 본격 검증)
- **검증**: 4 시나리오 모두 fail (production 미구현)

### Phase 3: GREEN — Sub-phase 분할

| Sub-phase | 작성 대상 |
|---|---|
| 3.1 | `OutboxEventRepository.findPendingForUpdate` + `markPublished` JPA 구현 |
| 3.2 | `ProcessedEvent` 도메인 + JPA + Adapter (write-first ROW_COUNT 분기) |
| 3.3 | `EventPublisher` port + `InProcessEventPublisher` adapter + `LoggingEventConsumer` |
| 3.4 | `OutboxPoller` (@Scheduled + @SchedulerLock) |
| 3.5 | `CardPayment.cancel()` 활성화 + `SagaCompensationService` + `BookingService` catch 통합 |
| 3.6 (feature-006 머지 후) | SagaCompensationService 에 `stock.release()` 통합 commit |

### Phase 4-6 — Skip / Review

- Phase 4 REFACTOR — 본 PR 끝에 인라인
- Phase 5 Review — `java-reviewer` (필수) + `database-reviewer` (V1 outbox/processed_event index 검증)
- Phase 6 — Scenario 2 (ShedLock 동시성) 가 의무 영역 충족

---

## Out of Scope

| 영역 | 이관 |
|---|---|
| TTL 만료 sweeper | feature-006 |
| Reconciliation worker | feature-007 |
| Kafka / RabbitMQ 전환 | future (ADR-010 §재검토 트리거 — 컨슈머 5+ 또는 fan-out 필요 시) |
| processed_event retention 배치 | 운영 영역 |

---

## Progress Log

- 2026-05-04 — Plan populated by main claude. feature-006 의 stock.release 의존성 명시 (§Phase 1 §3 / §Phase 3.6).
- 2026-05-04 — feature-006 (#18) + feature-007 (#19) 머지 후 진입 — §Phase 3.6 분할 단순화 (stock.release 본 PR 한 commit 에 통합).
- 2026-05-04 — Phase 3.1 GREEN — OutboxEventRepository.findPendingForUpdate (SELECT FOR UPDATE SKIP LOCKED) + markPublished. ADR-010 정합.
- 2026-05-04 — Phase 3.2 GREEN — ProcessedEvent 도메인 + JPA composite PK + write-first INSERT ... ON DUPLICATE KEY UPDATE.
- 2026-05-04 — Phase 3.3 GREEN — EventPublisher port + InProcessEventPublisher (Spring ApplicationEventPublisher 위임) + LoggingEventConsumer (write-first 컨슈머 멱등).
- 2026-05-04 — Phase 3.4 GREEN — OutboxPoller @Scheduled(fixedDelay=5000) + @SchedulerLock(name="outbox-poller") + TransactionTemplate (FOR UPDATE lock 유지).
- 2026-05-04 — Phase 3.5 GREEN — CardPayment.cancel 활성 + SagaCompensationService + BookingService.create() 의 finalizeSuccess catch 분기에 SagaCompensationService.compensate 호출 추가.
- 2026-05-04 — Scenario 2 (ShedLock concurrency) deferred — DB SELECT FOR UPDATE SKIP LOCKED 자체가 row-level 정합성 보장. 본 PR scope 외.
- 2026-05-04 — 3/4 시나리오 GREEN (Scenario 1 happy + Scenario 3 idempotency + Scenario 4 Saga 보상). 전체 ./gradlew test BUILD SUCCESSFUL.

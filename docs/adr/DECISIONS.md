# DECISIONS.md — 선착순 숙박 예약 시스템 설계 결정 기록

> **본 ADR은 AI 코딩 도구(Claude Code 등)와의 협업을 가정하여 작성되었다.** 한 파일 = 한 ADR 형식이며, 의도적 제외 영역과 코드 작성 시 전제할 가정을 *"결정의 한계"* 섹션에 명시했다.

## 개요

본 문서는 자정 오픈되는 한정 수량 숙박 상품의 선착순 예약 시스템 설계 결정을 ADR(Architecture Decision Record) 형식으로 기록한 인덱스다. 각 결정은 별도 ADR 문서로 봉인되어 있으며, 본 문서는 결정의 전체 흐름과 의존성을 정리한다.

## 시스템 환경 요약

- **트래픽**: 평시 50 TPS, 자정 burst 1~5분간 **500~1000 TPS 변동** (관측). 설계 capacity 기준 **1000 TPS 상한** (race / circuit breaker / pool sizing 분석은 이 상한 기준).
- **재고**: 초특가 숙소 10개 한정 (자정 오픈)
- **결제 수단**: 신용카드, Y페이, Y포인트 (복합 결제 일부 가능)
- **인프라**: 분산 환경, Spring Boot, Redis, RDB, PG 연동
- **핵심 SLO**: Fairness 100% > Availability 99.9%

## ADR 목록

| ADR | 파일명 | 주제 | Status |
|---|---|---|---|
| ADR-001 | [ADR-001-queue-length.md](./ADR-001-queue-length.md) | 큐 길이 제한 (재고의 3배) | **Deprecated** (by ADR-008) |
| ADR-002 | [ADR-002-redis-lua-atomic.md](./ADR-002-redis-lua-atomic.md) | Redis Lua Script 원자적 처리 | Accepted |
| ADR-003 | [ADR-003-payment-ttl.md](./ADR-003-payment-ttl.md) | 결제 TTL 5분 | Accepted |
| ADR-004 | [ADR-004-fairness-definition.md](./ADR-004-fairness-definition.md) | 약한 동등 시도 기회를 갖는 선착순 | Accepted |
| ADR-005 | [ADR-005-rate-limit.md](./ADR-005-rate-limit.md) | userId 기반 Token Bucket Rate Limit | Accepted |
| ADR-006 | [ADR-006-idempotency.md](./ADR-006-idempotency.md) | 멱등성 키 + Redis/DB 이중 계층 | Accepted |
| ADR-007 | [ADR-007-redis-fallback.md](./ADR-007-redis-fallback.md) | Redis Fallback (Sentinel + Resilience4j + Fail-Closed) | Accepted |
| ADR-008 | [ADR-008-stock-counter.md](./ADR-008-stock-counter.md) | 재고 카운터 기반 단일 진입 모델 | Accepted |
| ADR-009 | [ADR-009-payment-extensibility.md](./ADR-009-payment-extensibility.md) | 결제 수단 확장성 (두 계층 Strategy + Saga) | Accepted (정정됨) |
| ADR-010 | [ADR-010-event-processing.md](./ADR-010-event-processing.md) | 이벤트 처리 (Outbox + In-Process + 인터페이스 추상화) | Accepted |
| ADR-011 | [ADR-011-pg-idempotency-reconciliation.md](./ADR-011-pg-idempotency-reconciliation.md) | PG 멱등성 키 + Timeout Reconciliation | Accepted |
| ADR-012 | (planned) | 환불 실행 (cancel API 후속 도메인) | **Planned** — out of mandatory scope (forward path 구현 무관) |
| ADR-013 | [ADR-013-tdd-strategy.md](./ADR-013-tdd-strategy.md) | TDD 전략 (Mixed Test-First + ADR→테스트 매트릭스 + Gherkin-flavored JUnit 5) | Accepted |
| ADR-014 | [ADR-014-hexagonal-architecture.md](./ADR-014-hexagonal-architecture.md) | Hexagonal Architecture (Ports & Adapters) 정식 채택 | Accepted |

## 결정 흐름 — 의존성 그래프

```
[시스템 요구사항 분석]
    │
    ├─ 공정성 정의 (ADR-004)
    │       └─ "약한 동등 시도 기회를 갖는 선착순"
    │
    ├─ 진입 통제 (ADR-008)
    │       ├─ Redis 재고 카운터 기반
    │       └─ atomic DECR (ADR-002 Lua 패턴 재사용)
    │
    ├─ 1차 방어 (ADR-005)
    │       └─ userId Rate Limit (Token Bucket)
    │
    ├─ 결제 정합성 (ADR-006, 009)
    │       ├─ 멱등성 키 + DB unique (ADR-006)
    │       └─ Saga 보상 트랜잭션 (ADR-009)
    │           └─ ADR-006 멱등키 통합
    │
    ├─ 결제 수단 확장성 (ADR-009)
    │       ├─ External/Internal 두 계층 Strategy
    │       └─ Domain Value Object 검증
    │
    ├─ 결제 TTL (ADR-003)
    │       └─ 5분 (산업 표준 + burst window 정합)
    │
    ├─ 이벤트 처리 (ADR-010)
    │       ├─ Transactional Outbox 패턴
    │       ├─ In-Process 핸들러
    │       └─ EventPublisher 인터페이스 추상화
    │
    └─ Redis 장애 방어 (ADR-007)
            ├─ Sentinel HA (인프라 계층)
            ├─ Resilience4j 서킷 브레이커 (애플리케이션 계층)
            └─ Fail-Closed 통일 (Fairness 100% SLO)
```

## Failure Model

이 시스템의 모든 설계는 다음 세 가지 실패 전제를 공유한다:

- **At-least-once delivery**: 중복 전달 가능. 모든 Consumer는 idempotent해야 한다.
- **Eventual consistency**: 분산 컴포넌트 간 상태는 즉시 일치하지 않는다.
- **External system uncertainty**: PG는 네트워크 경계 너머에 있다. 호출 결과가 불확실할 수 있으며, 이 불확실성을 UNKNOWN/TIMEOUT 상태로 명시적으로 모델링한다.

## 핵심 결정 요약

### 1. 공정성 정의 (ADR-004)

본 명세의 *"선착순"*과 *"동등한 확률"*이 모순됨을 명시적으로 짚고, **약한 의미의 동등 시도 기회를 갖는 선착순**으로 정의. 도메인 관행(숙박)과 운영 단순성으로 선착순 채택, 동등성은 Rate Limit/큐 진입 cap으로 보장.

### 2. 진입 통제 (ADR-008)

ADR-001(큐 ZSET 모델)을 폐기하고 **Redis 재고 카운터 기반 단일 진입 모델**로 전환. 30만 명을 줄세우는 게 아니라 10명만 atomic DECR로 입장 허용. 결제 실패/TTL 만료 시 INCR로 재고 복구, 사용자는 새로고침 폴링으로 재참여.

### 3. 결제 수단 확장성 (ADR-009)

External(카드/Y페이)과 Internal(포인트)을 두 계층 Strategy로 분리. **PG 먼저 호출 → DB 트랜잭션 (Saga)**, DB 실패 시 PG 취소 API로 보상. Two-Phase Commit이 한국 PG에서 가능함을 확인했으나 운영 복잡도와 호환성 측면에서 Saga가 우월. 카카오페이 등 새 결제 수단 추가 시 신규 파일 1개만 추가.

### 4. 멱등성 (ADR-006)

클라이언트 발급 멱등키 + Redis 1차 캐시 + DB unique constraint 2차 보장. 3-state 응답 (200 OK / 409 Conflict / 422 Unprocessable). 핵심 필드(userId, productId, amount, paymentMethod, points)로 body 검증.

### 5. Redis 장애 방어 (ADR-007)

**Sentinel HA + Resilience4j 서킷 브레이커 + Bulkhead + Fail-Closed 통일**. 인프라 계층(Sentinel)과 애플리케이션 계층(서킷 브레이커) 다층 방어. Fail-Closed 통일은 *"Fairness 100% > Availability 99.9%"* SLO Decision의 결과.

### 6. 이벤트 처리 (ADR-010)

**Transactional Outbox 패턴**으로 PG-DB-이벤트 atomicity 보장. In-Process 핸들러(Spring ApplicationEventPublisher)로 50 TPS 환경에서 부담 0. EventPublisher 인터페이스 추상화로 향후 Kafka 도입 시 구현체만 교체. Kafka는 현재 fan-out 가치 부재로 미도입.

### 7. PG 멱등성 + Timeout Reconciliation (ADR-011)

PaymentAttempt를 도메인 Entity로 승격해 PG idempotency key(UUID)를 명시적으로 관리한다.
PG timeout 시 @Scheduled + ShedLock 기반 Reconciliation 워커가 PG 상태 조회로 최종 상태 확정.
NOT_FOUND는 FAILED로 처리하지 않는다. ADR-008/009의 "미결정 영역 — PG 응답 timeout" 완결.

### 8. TDD 전략 (ADR-013)

Mixed Test-First — 도메인·동시성·Saga·멱등성·Outbox·Reconciliation은 의무 RED, 인프라 boilerplate는 사후 허용.
분류 5종(Unit/Slice/Integration/Concurrency/Load) + Testcontainers MySQL/Redis + WireMock(PG).
"라인 커버리지 %" 대신 "핵심 시나리오 누락 0건" 정성 기준. ADR→테스트 매트릭스가 단일 소스.
시나리오 표현은 Gherkin (Cucumber-JVM 미사용 — BDD without stakeholders anti-pattern 회피).
운영: `docs/features/feature-NNN-*.md` 파일에 phase별 plan/진행 누적. `tdd-planner` agent가 관리, 코드 작성은 `test-author` agent.

### 9. Hexagonal Architecture (ADR-014)

4-layer (api/application/domain/infrastructure)를 ports & adapters로 정식 명명.
Driving port = use case (application service), Driving adapter = Controller.
Driven port = `<Aggregate>Repository`, `EventPublisher`, `ExternalPaymentMethod` (domain interfaces).
Driven adapter = JpaRepository, InProcessEventPublisher, CardPayment, MockPgClient (infrastructure 구현체).
Domain은 외부 기술 의존성 0 원칙. ADR-009/010/011이 모두 본 패턴 위에 결정 — 본 ADR이 기반 명시.

### 10. 고가용성 다층 방어 (ADR-005, 007, 008 + 결정의 한계 §1 종합)

설계 capacity 1000 TPS 상한 burst를 견디도록 **진입 → 처리 → 장애 격리 → 무결성 보호** 4층 방어를 직렬 구성한다. 각 층은 다른 위협을 다루며(Defense in Depth), 어떤 층이 무력화되어도 다음 층이 boundary를 보장한다. REQUIREMENTS.md Req 2의 *"DECISIONS.md 기술 항목"* 요구를 본 절로 충족한다.

#### 방어 시퀀스

| 순서 | 층 | 도구 | 커버 위협 | 임계값 / 근거 |
|---|---|---|---|---|
| 1 | Rate Limit | userId Token Bucket Lua (ADR-005) | 봇/매크로 동시 다발 시도 | 3/s + burst 5. 5분 윈도우당 ~900회 — 정상 사용자 충분, 봇 무의미 |
| 2 | 재고 카운터 진입 | Redis atomic DECR Lua (ADR-008) | 초과 판매 / race condition | 정확히 10명만 통과. 결제 실패/TTL 만료 시 INCR 반납 |
| 3 | Bulkhead | Resilience4j (ADR-007) | Redis slow death 시 Tomcat 스레드 점유 | `maxConcurrentCalls 100` (Tomcat 200의 50%) — 다른 요청 처리 여력 보장 |
| 4 | Circuit Breaker | Resilience4j TIME_BASED 5초 (ADR-007) | cascading failure / 응답 지연 누적 | `failureRate 50%` / `slowCallDuration 1s` (Redis 정상 5ms 대비 200배) / `waitDurationInOpenState 5s` (Sentinel failover 5~15s 정합) |
| 종단 | Fail-Closed | 모든 Redis 의존 컴포넌트 503 응답 (ADR-007) | 무결성 훼손 (이중 결제 / 초과 판매) | `Fairness 100% > Availability 99.9%` SLO Decision 의 결과 |

#### 인프라 보조 (계층 1+ 보강)

- **Sentinel HA**: Master 1 + Replica 2 + Sentinel 3 — Master 다운 5~15s 내 자동 failover. `down-after 5초` / `quorum 2` / `min-replicas-to-write 1` (split-brain 방어, ADR-007)
- **수평 확장 가정**: 인스턴스 4~5대 + 로드 밸런서. 모든 인스턴스가 동일 Redis Master/Replica + 동일 RDB 공유. stateless (세션은 JWT 또는 Redis) — 결정의 한계 §1
- **분산 락**: Outbox 폴러 / Reconciliation 워커는 ShedLock으로 다중 인스턴스 중복 실행 방지 (ADR-010, ADR-011)

#### TPS burst 흡수 경로

```
1000 TPS 상한 ─→ [Rate Limit ~900/userId per 5min cap] ─→ [재고 10개 atomic DECR]
              ─→ [Bulkhead 100 동시 호출] ─→ [Circuit Breaker 50% failureRate / 1s slow]
              ─→ DB 트랜잭션 (Outbox INSERT 포함, ADR-010)
                                                    ↓ slow death 감지
                                                   503 (Fail-Closed)
```

burst window 1~5분간 누적 ~30만 요청 중 성공 가능 = 재고 10개. 99.997%는 어떤 층에서든 차단되거나 SOLD_OUT 응답을 받는다 (ADR-001 deprecated 분석 그대로 유효).

#### 재검토 트리거

- 평시 TPS가 200 이상으로 증가 → Layer 4 임계값(`minimumNumberOfCalls`, `slidingWindowSize`) 재평가
- 인스턴스 수가 10대 이상 → Sentinel quorum / `min-replicas-to-write` 재검토
- 서킷 OPEN 발생 빈도 분기당 10회 이상 → Bulkhead `maxConcurrentCalls` 또는 Tomcat 스레드 풀 확장 검토
- 단일 userId의 burst 5 토큰이 봇 차단에 부족 → Rate Limit 다층(`IP+userId`) 도입 검토 (ADR-005 옵션 D)

본 절은 ADR-005/007/008 + 결정의 한계 §1 에 분산된 메커니즘을 한 시퀀스로 종합한 것이며, 개별 ADR 본문은 변경하지 않는다.

## 핵심 패턴

### 패턴 1: 4축 분리

대부분의 결정을 4가지 직교축으로 분리해 토론. 각 축의 재검토 시점이 다르므로 한 축을 재검토할 때 다른 축에 영향 없음. (예: ADR-009의 Strategy 분리 단위 / 트랜잭션 모델 / 검증 위치 / OCP 측정)

### 패턴 2: Out of Scope 명시

미래 확장 가능 영역은 *"Out of Scope"*로 명시하고 *"추가 시 ~ 필요"*까지 박음. (예: ADR-006의 쿠폰, ADR-009의 카카오페이)

### 패턴 3: 부정 결과 명시

모든 결정에 *"긍정 결과"*뿐 아니라 *"부정 결과"*를 명시해 trade-off 투명화. ADR이 후임자에게 *"이 결정의 약점은 X"*를 알림.

### 패턴 4: 이벤트 기반 재검토 시점

시간 기반(*"6개월 후"*) 재검토가 아니라 **관찰 가능한 트리거** 명시. (예: *"평시 TPS가 1000 이상으로 증가할 때"*, *"컨슈머 수가 3개 이상으로 증가할 때"*)

### 패턴 5: 자기 교정 정직성

검증 없는 단정 발견 시 ADR을 정정. ADR-009의 *"한국 PG가 Two-Phase Commit 지원 안 함"* 단정이 부정확했음을 후속 리서치로 확인하고 정정 사유 명시.

## 향후 적용 영역 (별도 리서치 후 적용)

본 ADR 문서는 설계 결정에 집중한다. 다음 영역은 코드 구현/운영 단계에서 별도 리서치 후 적용한다.

### 관측성 (Observability)
- 비즈니스 메트릭: 큐 진입율, 결제 성공률, TTL 만료 비율, 재고 소진 시간
- 기술 메트릭: Redis 응답 시간, 서킷 브레이커 상태, Outbox 지연, Sentinel failover
- 분산 추적: 한 요청의 큐 → Booking → PG → DB 흐름
- 구조화 로깅: correlation ID + 결제 ID + 사용자 ID
- 알림 정책: 어떤 임계값에서 운영자 호출

### 테스트 전략

ADR-013에서 결정됨 — Mixed Test-First, 5분류, Testcontainers + WireMock, Gherkin-flavored JUnit 5.
운영은 `docs/features/` feature 파일 + `tdd-planner` / `test-author` agent.

- 카오스 테스트 (Sentinel failover, Redis 다운 시나리오) — ADR-013 Out of Scope, 별도 영역

### 운영 절차 (Runbook)
- PG timeout reconciliation 절차
- Outbox INSERT 실패 시 복구
- Sentinel failover 후 데이터 cross-check
- Saga 보상 트랜잭션 수동 재시도
- 서킷 브레이커 강제 OPEN/CLOSE 절차

## 결정의 한계 — 의도적 제외 영역과 가정

본 ADR 문서가 의도적으로 다루지 않는 영역과 그에 따라 코드 작성 시 전제해야 할 가정을 명시한다. **이 섹션은 AI 코딩 도구(Claude Code, Copilot 등)와 신규 개발자가 ADR에 명시되지 않은 영역에서 잘못된 추론을 하지 않도록 가정을 박는 역할을 한다.**

### 1. 배포/확장 환경

**제외 사유**: 본 ADR은 코드 설계 결정에 집중하며, 인프라 구성은 별도 ADR 영역.

**전제할 가정**:
- 본 시스템은 단일 코드베이스이지만 **다중 인스턴스 수평 확장**을 가정한다.
- 설계 capacity **1000 TPS 상한** burst 처리를 위해 인스턴스 4~5대 + 로드 밸런서 구성을 가정.
- 모든 인스턴스는 동일 Redis Master/Replica, 동일 RDB를 공유.
- 세션은 stateless (JWT 또는 Redis 세션) 가정. 인스턴스 간 sticky session 없음.

**AI/개발자가 주의할 점**:
- @Scheduled로 Outbox 폴링을 구현할 때 **다중 인스턴스 환경에서 중복 실행되지 않도록 분산 락 필요** (ShedLock, SELECT FOR UPDATE SKIP LOCKED 등).
- In-Process 이벤트 핸들러는 인스턴스별로 동작. 한 인스턴스의 이벤트는 그 인스턴스에서만 처리됨.
- 캐시(Redis 외 로컬 캐시)는 인스턴스 간 동기화 안 됨. 일관성 필요한 데이터는 Redis 사용.

### 2. PG 연동 디테일

**제외 사유**: 본 시스템은 PG 연동을 mocking 가정. 실제 SDK 통합은 별도 작업.

**전제할 가정**:
- PG 호출은 동기 HTTP API (예: 토스페이먼츠 결제 승인 API)로 가정.
- 응답 시간 평균 1~3초, timeout 10초 가정.
- 결제 인터페이스는 ADR-009의 ExternalPaymentMethod 추상화 따름.
- mocking 환경에서도 timeout/실패 시나리오는 동일하게 발생 가능 가정.

**AI/개발자가 주의할 점**:
- PG 호출 코드는 인터페이스 뒤로 숨겨야 함 (테스트 시 mocking 가능).
- 실제 PG 통합 시 ADR-009의 *"승인-매입 분리"* 가능성 재평가 필요.
- 토스페이먼츠 *"망취소(Network Cancellation)"* 시나리오는 ADR-009/010에서 Saga + Outbox로 해결.

**미결정 영역 — PG 응답 timeout 처리**:
- ADR-008의 *"PG 유예 60초 후 강제 회수 + PG 취소 요청"*은 PG가 응답을 보낸 시나리오만 다룬다.
- ADR-009는 PG 응답 timeout 시 *"상태 조회 API → 보상/확정 분기"*가 필요함을 명시하면서 *"본 ADR의 범위 밖, 운영 절차"*로 분리했다.
- **AI/개발자는 ADR-008의 *"강제 회수 + PG 취소 요청"*을 PG timeout 시나리오에 임의 적용해서는 안 된다.** PG 실제 상태와의 불일치 위험이 있다.
- ADR-011에서 timeout reconciliation 절차 결정됨.

### 3. DB 부하 관리

**제외 사유**: DB 튜닝은 운영 영역이며 코드 결정과 별개.

**전제할 가정**:
- RDB는 **MySQL 8.0+ (또는 MariaDB 10.6+)** 가정. 본 시스템 요구사항(MySQL/MariaDB 한정) 준수.
- 본 환경에서 `SELECT ... FOR UPDATE SKIP LOCKED`, `JSON` 타입, `INSERT ... ON DUPLICATE KEY UPDATE` 모두 지원되어 ADR-008/010 패턴 충족.
- HikariCP Connection Pool 사용, 인스턴스당 30~50 connection 가정.
- 설계 capacity **1000 TPS 상한** burst 시 DB write 부하는 인스턴스 분산으로 완화.
- 향후 Read Replica 도입 시 booking 조회는 Replica로, write는 Master로 분리 가능.

**AI/개발자가 주의할 점**:
- 인덱스 설계 필수: `(idempotency_key)`, `(outbox.status, created_at)`, `(booking.user_id)` 등.
- 트랜잭션 범위 최소화 (PG 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안).
- N+1 쿼리 회피 (특히 결제 수단 복합 조회).

### 4. 관측성 (Observability)

**제외 사유**: 좋은 도구를 적절히 적용하는 영역. trade-off보다 리서치 후 적용.

**전제할 가정**:
- Spring Boot Actuator 기반 메트릭 노출.
- Prometheus + Grafana 조합 가정 (산업 표준).
- 분산 추적은 Spring Cloud Sleuth 또는 Micrometer Tracing.
- 로깅은 구조화 로그 (JSON 포맷, ELK 또는 CloudWatch).

**AI/개발자가 주의할 점**:
- 비즈니스 메트릭과 기술 메트릭 분리 (큐 진입율 vs Redis 응답 시간).
- 모든 외부 호출(Redis, DB, PG)에 응답 시간 메트릭 + 실패 카운터 필수.
- 서킷 브레이커 상태 변경(ADR-007)은 알림 연동 필수.
- correlation ID는 모든 로그에 포함 (한 요청 추적 가능해야 함).

### 5. 테스트 전략

**제외 사유**: 좋은 도구를 적절히 적용하는 영역.

**전제할 가정**:
- 단위 테스트는 Mockito 등으로 외부 의존성 mocking.
- 통합 테스트는 Testcontainers로 실제 Redis/DB 띄움.
- 부하 테스트는 k6 또는 Gatling으로 **상한 1000 TPS 시뮬레이션** (variant: 500~1000 TPS 변동 시나리오 권장).
- 카오스 테스트는 운영 환경 진입 전 별도 단계.

**AI/개발자가 주의할 점**:
- 동시성 테스트 필수 영역: ADR-002 Lua atomic, ADR-008 재고 카운터, ADR-006 멱등성.
- Saga 보상 트랜잭션은 *"DB 커밋 실패 시 PG 취소 호출"* 시나리오 반드시 테스트.
- 서킷 브레이커는 *"50% 실패율 도달 시 OPEN 전환"* 테스트.
- Outbox는 *"DB 실패 시 이벤트 영구 유실 0% 검증"* 테스트.

### 6. 운영 절차 (Runbook)

**제외 사유**: 코드가 아닌 사람의 절차 영역.

**전제할 가정**:
- 운영자가 24시간 대응 가능한 알림 채널(Slack/PagerDuty) 구축.
- 데이터 불일치 발견 시 수동 reconciliation 도구(SQL 스크립트 등) 별도 작성.

**AI/개발자가 주의할 점**:
- 코드는 운영자가 *"수동 개입 가능한 진입점"* 제공해야 함.
- Saga 보상 수동 재시도, 서킷 브레이커 강제 OPEN/CLOSE 등의 admin API 필요.
- 모든 비정상 시나리오는 로그에 명확히 식별 가능한 마커 포함 (예: `[OUTBOX_INSERT_FAILED]`).

### 7. 결제 수단별 비즈니스 정책

**제외 사유**: 비즈니스 도메인 영역.

**전제할 가정**:
- 환불 정책, 부분 취소 정책, 카드사별 한도 등은 비즈니스 팀 정의.
- 결제 시스템은 정책을 *"실행"*만 하며, *"정의"*하지 않음.

**AI/개발자가 주의할 점**:
- 결제 수단별 분기 로직은 ADR-009 두 계층 Strategy로 처리.
- 새 정책 추가 시 PaymentComposition의 검증 규칙에만 추가, 다른 코드 수정 최소화.

### 8. 회계/정산

**제외 사유**: 결제 시스템과 별도 도메인.

**전제할 가정**:
- 매출 인식, 정산 주기, 세무 처리 등은 회계 시스템 영역.
- 결제 시스템은 *"결제 이벤트 발행"* 까지의 책임. 정산은 별도 컨슈머가 처리.

**AI/개발자가 주의할 점**:
- 정산 시스템 추가 시 ADR-010의 EventPublisher 인터페이스로 Kafka 등 메시지 브로커 도입 검토.
- 결제 이벤트의 payload는 정산 시스템이 필요한 정보(amount, paymentMethod, paidAt 등) 포함.

---

이 영역들은 본 시스템의 *"선착순 예약 흐름"* 관심사 밖이지만, 코드 작성 시 위 가정을 전제로 한다. AI 코딩 도구를 활용할 때 이 가정을 함께 입력해 잘못된 추론을 방지한다.

# ADR-2026-05-03-014: Hexagonal Architecture — Ports & Adapters 정식 채택

## Status

Accepted (2026-05-03)

## Context

ADR-009(결제 수단 확장성), ADR-010(이벤트 처리), ADR-011(PG idempotency + Reconciliation)은 모두 *port-adapter* 패턴 위에 결정됐다:

- **ADR-009**: `PaymentMethod` / `ExternalPaymentMethod` / `InternalPaymentMethod` 인터페이스 + `CardPayment` / `YpayPayment` / `PointPayment` 구현체
- **ADR-010**: `EventPublisher` 인터페이스 + `InProcessEventPublisher` 구현체 (향후 `KafkaEventPublisher`로 교체 가능)
- **ADR-011**: `payment_attempt`의 `external_payment_id` — 외부 PG 시스템과의 통신 추적
- ARCHITECTURE.md §3 패키지 구조: `domain/<aggregate>/Repository` (interface) + `infrastructure/persistence/<Aggregate>JpaRepository` (구현)

→ 이 모든 결정의 기반이 되는 **헥사고날 (Ports & Adapters) 아키텍처** 자체는 ADR로 봉인되지 않았다. ADR 12개 전체에 *"헥사고날/hexagonal/port/adapter"* 키워드 검색 0건. ARCHITECTURE.md도 4-layer 구조만 정의하고 헥사고날 용어 미사용.

기반 결정 누락의 결과:
- 새 결정 시 헥사고날 컨벤션이 어디서 근거를 갖는지 불명확 → 결정마다 즉흥
- "domain port", "infrastructure adapter" 같은 용어가 일부 문서(feature-001)에만 출현 → 일관성 약화
- 컨벤션 문서(`CONVENTIONS-FILE.md`/`CONVENTIONS-CODE.md`) 작성 시 port/adapter 네이밍 규칙의 ADR 근거 부재

본 ADR은 이 기반 결정을 명시화한다.

## Options Considered

### 축 1 — 명시 여부

| 옵션 | 판단 |
|---|---|
| A. 명시 안 함 (현 상태 유지) | ADR-009/010/011 결정의 기반이 ADR로 봉인 안 됨 — 일관성 약점, 컨벤션 근거 부재 → 기각 |
| B. ARCHITECTURE.md만 헥사고날 용어 추가 | ADR 단일 진실의 원천 원칙 약화 — 결정은 ADR이 정식 봉인해야 함 → 기각 |
| **C. ADR-014 명시 + ARCHITECTURE.md / CONVENTIONS 정합** | 채택 — 결정 봉인 + 문서 sync |

### 축 2 — 변형 선택 (Hexagonal vs Clean vs Onion)

| 옵션 | 핵심 |
|---|---|
| **Hexagonal (Ports & Adapters)** — Alistair Cockburn (2005) | Driving / Driven port 명시. Adapter는 port를 외부 기술에 연결. 도메인이 중심. |
| Clean Architecture — Robert Martin (2012) | Entities → Use Cases → Interface Adapters → Frameworks. 동심원. |
| Onion Architecture — Jeffrey Palermo (2008) | Domain → Domain Services → Application Services → Infrastructure. |

세 변형 모두 *"도메인 중심, 의존성 역전"*을 공유. **Hexagonal 채택** 근거:
- Driving / Driven 구분이 우리 시스템의 **외부 경계 (HTTP 입력 vs PG/Redis/DB 호출)** 를 가장 명확히 표현
- ADR-007(Redis Fail-Closed), ADR-009(Saga), ADR-010(Outbox), ADR-011(Reconciliation)이 모두 *driven adapter* 보호·격리 결정 — Hexagonal 용어와 1:1 매핑
- "port" / "adapter" 용어가 가장 직관적, 학습 곡선 낮음

### 축 3 — 패키지 구조 변경 여부

| 옵션 | 판단 |
|---|---|
| A. 현행 4-layer 유지 + 헥사고날 라벨만 부여 | ARCHITECTURE.md 패키지 구조가 이미 헥사고날과 정합 — 추가 디렉토리 불필요. 변경 비용 0. → **채택** |
| B. `domain/port/` / `infrastructure/adapter/` sub-디렉토리 추가 | 명시성 ↑, 그러나 패키지 깊이 증가, 기존 ARCHITECTURE.md 수정 부담. 현행으로 충분 → 기각 |

## Decision

**축 1: C / 축 2: Hexagonal / 축 3: A** — 현행 4-layer를 헥사고날 (Ports & Adapters)로 정식 명명. 패키지 구조 변경 없음.

### 핵심 명명

| 용어 | 본 프로젝트 매핑 |
|---|---|
| **Driving port** | 시스템이 *제공하는* 사용 사례 인터페이스. e.g., `BookingService.createBooking()` — 사용자(외부)가 시스템을 *driving* |
| **Driving adapter** | Driving port를 외부 입력 채널에 연결. e.g., `BookingController` (Spring `@RestController` — HTTP 입력을 use case 호출로 변환) |
| **Driven port** | 시스템이 *호출하는* 외부 시스템 인터페이스. e.g., `BookingRepository`, `EventPublisher`, `ExternalPaymentMethod` (domain interface) |
| **Driven adapter** | Driven port의 구체 구현. e.g., `BookingJpaRepository`, `InProcessEventPublisher`, `CardPayment`, `MockPgClient` (infrastructure 구현체) |
| **Use Case** | Application service. Driving port 구현 + driven port 조율. e.g., `BookingService`, `IdempotencyKeyService` |
| **Domain** | 비즈니스 불변식 + 모델 + driven port 인터페이스. 외부 기술 의존성 0. e.g., `Booking`, `PaymentComposition`, `BookingRepository` (interface) |

### 패키지 구조 (ARCHITECTURE.md §3 정식 매핑)

```
com.booking/
├── api/                          # Driving adapters (HTTP)
│   ├── <aggregate>/
│   │   ├── <Aggregate>Controller.java
│   │   └── dto/
│   └── GlobalExceptionHandler.java
│
├── application/                  # Use Cases (driving port 구현)
│   └── <aggregate>/<UseCase>Service.java
│
├── domain/                       # Domain models + Driven ports
│   └── <aggregate>/
│       ├── <Aggregate>.java                    # Aggregate Root
│       ├── <ValueObject>.java                  # VO
│       ├── <Aggregate>Repository.java          # Driven port (persistence)
│       └── <Action>Port.java                   # Driven port (외부 시스템)
│
└── infrastructure/               # Driven adapters
    ├── persistence/<Aggregate>JpaRepository.java
    ├── redis/<Aggregate>LuaScript.java
    ├── pg/<System>PgAdapter.java
    ├── event/<Publisher>EventPublisher.java
    └── resilience/ResilienceConfig.java        # Cross-cutting (driven adapter 보호)
```

### Port 네이밍 규칙

| 종류 | 네이밍 | 예시 |
|---|---|---|
| Driving port (Use Case) | `<UseCase>UseCase` 또는 `<Aggregate>Service` (interface) | `BookingService` |
| Driven port (persistence) | `<Aggregate>Repository` (interface) | `BookingRepository`, `OutboxRepository` |
| Driven port (외부 시스템) | `<Action>Port` 또는 의미 interface | `EventPublisher`, `ExternalPaymentMethod` |

### Adapter 네이밍 규칙

| 종류 | 네이밍 | 예시 |
|---|---|---|
| Driving adapter | `<Aggregate>Controller` | `BookingController`, `AdminController` |
| Driven adapter (persistence) | `<Aggregate>JpaRepository` | `BookingJpaRepository` |
| Driven adapter (외부 시스템) | `<System><Role>Adapter` 또는 의미 class | `TossPgAdapter`, `MockPgClient`, `InProcessEventPublisher`, `CardPayment` |

### 의존성 방향 (헥사고날 핵심)

```
Driving adapter (api)         Driven adapter (infrastructure)
       ↓                                ↑
       ↓ depends on                     ↑ implements
       ↓                                ↑
       └─────→ Use Case ─────→ Driven port (domain interface)
              (application)           ↑
                                      ↑
                                Domain models
                                (domain — 의존성 0)
```

핵심 규칙:
- **Domain은 외부 기술에 의존하지 않는다** — JPA annotation, Spring `@Component`, Redis 클라이언트 등 import 금지
- **의존성 역전**: Use Case는 driven port (interface)에 의존하고, driven adapter (구현)는 driven port를 implement
- **Driving adapter ↔ Use Case**: HTTP / CLI / 메시지 큐 등 어떤 driving 채널이든 Use Case 인터페이스만 호출

### 기존 ADR과의 정합 (cross-reference)

- **ADR-007** (Redis Fail-Closed): Resilience4j Circuit Breaker는 *driven adapter*(Redis 호출 컴포넌트)에 적용. Fallback 503은 driven adapter 격리.
- **ADR-009** (Saga): `ExternalPaymentMethod` = driven port. PG 호출 → DB 트랜잭션 → 보상 호출은 모두 driven port 조율 (Use Case 책임).
- **ADR-010** (Outbox): `EventPublisher` = driven port. `InProcessEventPublisher` = driven adapter. Kafka 도입 시 `KafkaEventPublisher` adapter로 교체.
- **ADR-011** (PG idempotency + Reconciliation): `payment_attempt.external_payment_id` — driven adapter 통신 추적. Reconciliation 워커는 use case (`@Scheduled` driving 시점은 Spring 인프라).

## Consequences

**긍정 결과**:
- ADR-009/010/011 결정의 기반이 ADR로 봉인 — 일관성 확보
- Port / adapter 네이밍 규칙이 ADR 근거를 가짐 → CONVENTIONS-FILE.md / CONVENTIONS-CODE.md가 본 ADR을 인용
- 새 외부 시스템 통합 시 *driven port + driven adapter 추가* 패턴 명확 (e.g., 카카오페이 = 새 driven adapter)
- 도메인의 외부 기술 의존성 0 원칙 명시 → Pure unit test 용이 (Mockito mock 없이도 도메인 단독 테스트 가능)

**부정 결과**:
- 신규 개발자에게 "driving / driven" 용어 학습 부담 (단, 일반 4-layer 멘탈 모델로도 작업 가능 — 본 ADR은 명명 규칙 추가일 뿐)
- ADR-009/010 본문이 본 ADR보다 먼저 작성됐으므로 헥사고날 용어가 명시적이지 않음 — 본 ADR이 cross-reference로 보강 (기존 ADR 본문 미변경)
- "port" / "adapter" 용어가 Spring `@Component` 같은 인프라 annotation과 혼동 가능 — 본 ADR §명명에서 정확한 매핑 명시

**재검토 시점**:
- 도메인이 5+ aggregate로 늘고 cross-aggregate 호출이 빈번해질 때 — 모듈화 또는 마이크로서비스 분리 검토 (각 모듈이 독립 헥사고날)
- CQRS 도입 검토 시점 — Read 모델과 Write 모델의 port 분리 필요성

## Out of Scope

- **CQRS / Event Sourcing 도입** — 현 시스템 복잡도(50 TPS / 1000 TPS burst, 단일 도메인) 미도달
- **모듈러 모놀리스** — 단일 코드베이스로 충분. 도메인이 분리될 임계 도래 시 검토
- **Anti-corruption Layer (DDD)** — 외부 시스템(PG)과의 모델 차이가 적음 (`payment_attempt.external_payment_id` 단일 필드 추적). 도입 비용 > 가치
- **Driven port의 sync vs async 구분** — 현재 모두 sync. async 필요 시(Kafka 등) `EventPublisher` adapter 교체로 흡수

## 관련 ADR

- **ADR-007**: Redis Fail-Closed — driven adapter 격리 + Circuit Breaker
- **ADR-009**: 결제 수단 두 계층 Strategy + Saga — `ExternalPaymentMethod` / `InternalPaymentMethod` driven port
- **ADR-010**: Outbox + EventPublisher 추상화 — `EventPublisher` driven port
- **ADR-011**: PG idempotency + Reconciliation — driven adapter 통신 추적
- **ADR-013**: TDD 전략 — domain layer 테스트 = port mock 또는 in-memory adapter 가능 (외부 의존성 0 원칙)
- **ARCHITECTURE.md** §2~§4: 본 ADR과 정합되도록 보강 (헥사고날 용어 명시)

## References

- Alistair Cockburn, *"Hexagonal Architecture"* (2005) — https://alistair.cockburn.us/hexagonal-architecture/
- Robert Martin, *"The Clean Architecture"* (2012) — 비교 참조
- Vlad Khononov, *"Learning Domain-Driven Design"* (2021) — Aggregate / Port-Adapter 정합 설명

# Conventions — File

파일을 **생성/위치/구조화** 할 때 적용. 코드 작성 시 컨벤션은 `CONVENTIONS-CODE.md` 참조.

본 파일은 **단일 진실의 원천**. ADR/agent/template 등에서 동일 컨벤션을 인용할 때 본 파일을 ref로 가리킨다.

---

## §1. Markdown 파일 형식

### ADR

```
# ADR-YYYY-MM-DD-NNN: <Title>

## Status
Accepted (YYYY-MM-DD) / Accepted (정정됨 YYYY-MM-DD) / Deprecated (by ADR-NNN) / Superseded (by ADR-NNN)

## Context
(왜 이 결정이 필요한지)

## Options Considered
### 축 N — <분리 축>
| 옵션 | 판단 |
|---|---|
| A. ... | 기각 사유 |
| **B. ...** | 채택 |

## Decision
(결정 내용)

## Consequences
**긍정 결과** / **부정 결과** / **재검토 시점**

## Out of Scope
(의도적 제외)

## 관련 ADR
(cross-reference)
```

### Feature

`_template.md` 참조. 핵심 섹션:

```
# Feature NNN: <Name>

| Status | Owner | Created | Last Updated |
|---|---|---|---|

> Self-contained 안내 박스 (외부 대화 참조 금지)

## Request
(원래 사용자 요청, 변경 없이 보존)

## Feature
(Gherkin Scenario 형식)

### Scenario Map
(시나리오 ↔ 테스트 메소드 1:1 매핑 표)

## Execution Plan (TDD)
### Phase 0~6 (+ sub-phase 3.1, 3.2, ...)

## Progress Log
(append-only)

## Outcome
(feature Done 시 채움)
```

### CONVENTIONS

```
# Conventions — <Type>
(서론)
---
## §1. ...
## §2. ...
```

---

## §2. Korean / English mix

- **결정·기술 키워드 영어**: Saga, Outbox, Reconciliation, port, adapter, Idempotency, JPA, Bean Validation, Lua atomic
- **설명·주석 한글**
- **인용 ADR / 파일 경로는 백틱**: `ADR-006`, `docs/ERD.md`, `scripts/execute.py`
- **혼합 예시**: *"ADR-009의 Saga 보상 흐름은 PG 호출 후 DB 트랜잭션 실패 시 적용된다."*

---

## §3. Mermaid 사용 표준

| 다이어그램 종류 | Mermaid 타입 |
|---|---|
| Entity Relationship Diagram | `erDiagram` |
| 상태 머신 | `stateDiagram-v2` |
| 흐름 / 상호작용 | `sequenceDiagram` |
| 패키지 / 계층 구조 | **텍스트 ASCII 박스** (Mermaid `classDiagram` 비권장 — 가독성) |

코드 블록 형식: ```` ```mermaid ```` 시작 + 종료.

---

## §4. Java 파일 위치 (헥사고날 패키지 — ADR-014)

```
com.booking/
├── api/                                # Driving adapters (HTTP)
│   ├── <aggregate>/<Aggregate>Controller.java
│   ├── <aggregate>/dto/<Action><Entity>Request.java
│   ├── <aggregate>/dto/<Entity>Response.java
│   └── GlobalExceptionHandler.java
│
├── application/                        # Use Cases (driving port 구현)
│   └── <aggregate>/<UseCase>Service.java
│
├── domain/                             # Domain models + Driven ports
│   └── <aggregate>/
│       ├── <Aggregate>.java            # Aggregate Root
│       ├── <ValueObject>.java          # Value Object
│       ├── <Aggregate>Repository.java  # Driven port (persistence interface)
│       └── <Action>Port.java           # Driven port (외부 시스템 호출 interface)
│
└── infrastructure/                     # Driven adapters
    ├── persistence/<Aggregate>JpaRepository.java
    ├── redis/<Aggregate>LuaScript.java
    ├── pg/<System>PgAdapter.java
    ├── event/<Publisher>EventPublisher.java
    └── resilience/ResilienceConfig.java
```

**원칙**:
- Domain은 외부 기술에 의존 X (JPA annotation / Spring `@Component` / Redis 클라이언트 import 금지) — ADR-014
- Driven port는 domain 인터페이스 / Driven adapter는 infrastructure 구현체 (의존성 역전)

---

## §5. Test 파일 위치

| 분류 | 위치 | 예시 |
|---|---|---|
| Unit | `src/test/java/com/booking/<aggregate>/<Class>Test.java` | `BookingTest.java` |
| Slice | 같은 위치 (`@WebMvcTest` / `@DataJpaTest` annotation) | `BookingControllerTest.java` |
| Integration | `src/test/java/com/booking/integration/<Feature>IntegrationTest.java` | `BookingIdempotencyIntegrationTest.java` |
| Concurrency | `src/test/java/com/booking/concurrency/<Feature>ConcurrencyTest.java` | `BookingIdempotencyConcurrencyTest.java` |
| Test Data Builder | `src/test/java/com/booking/testsupport/<Aggregate>TestDataBuilder.java` | `BookingTestDataBuilder.java` |
| Load test (k6) | `load-test/<feature>.js` | `load-test/booking-burst.js` |

---

## §6. DDL 파일 구조

- 매 `CREATE TABLE`에 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` 명시
- 컬럼 → PRIMARY KEY → UNIQUE → INDEX → FK 순서로 정의
- 마이그레이션 파일: `src/main/resources/db/migration/V<N>__<snake_case_name>.sql` (Flyway 관행)
- 단일 테이블 = 단일 마이그레이션 파일 (rollback 단위 명확)
- 각 컬럼에 의미 주석 필수 (특히 status enum, JSON snapshot)

상세 DDL 코드 컨벤션은 `CONVENTIONS-CODE.md` §7.

---

## §7. Configuration 파일

- **`application.yml` 권장** (properties보다 가독성 — 들여쓰기 + comment)
- 환경별: `application-{profile}.yml` (`test`, `prod`, `local`)
- Secret은 환경 변수 또는 `.env` (git 커밋 금지 — `.gitignore` 확인)
- Spring profile: `@ActiveProfiles("test")` — Testcontainers 통합 테스트 시

---

## §8. 파일 네이밍

| 종류 | 규칙 | 예시 |
|---|---|---|
| Markdown | `kebab-case.md` (prefix 있으면 `<prefix>-NNN-kebab-case.md`) | `feature-001-idempotency-handling.md`, `ADR-014-hexagonal-architecture.md` |
| Java | `PascalCase.java` | `BookingService.java`, `BookingTest.java` |
| SQL 마이그레이션 | `V<N>__snake_case.sql` | `V1__create_booking.sql` |
| Bash 스크립트 | `kebab-case.sh` | `feature.sh` |
| Python 스크립트 | `snake_case.py` | `execute.py`, `adr_hook.py` |
| YAML / JSON | `kebab-case.yml` 또는 `snake_case.yml` (프로젝트 통일) | `application.yml` |

---

## §9. 디렉토리 컨벤션

```
booking/
├── CLAUDE.md                    # Root navigation map (~50 lines)
├── README.md                    # 프로젝트 개요·실행
├── .gitignore
├── pom.xml / build.gradle       # 빌드 (향후)
├── docs/
│   ├── CLAUDE.md                # docs/ 작업 시 자동 로드
│   ├── ARCHITECTURE.md
│   ├── ERD.md
│   ├── TEST_MATRIX.md
│   ├── CONVENTIONS-FILE.md      # (본 파일)
│   ├── CONVENTIONS-CODE.md
│   ├── adr/
│   │   ├── DECISIONS.md
│   │   └── ADR-NNN-*.md
│   └── features/
│       ├── _template.md
│       ├── feature-NNN-*.md     # active
│       └── closed/              # 완료 archive
├── src/
│   ├── CLAUDE.md                # 코드 작성 시 자동 로드 (현재 stub)
│   ├── main/java/com/booking/
│   │   ├── api/
│   │   ├── application/
│   │   ├── domain/
│   │   └── infrastructure/
│   └── test/java/com/booking/
│       ├── <aggregate>/
│       ├── integration/
│       ├── concurrency/
│       └── testsupport/
├── scripts/
│   ├── CLAUDE.md                # scripts/ 작업 시 자동 로드
│   ├── execute.py
│   ├── README.md
│   └── adr_hook.py
├── load-test/                   # k6 스크립트
├── logs/                        # execute.py 산출 (gitignore)
└── .claude/
    ├── agents/                  # project-local agents
    │   └── <agent>.md
    └── commands/                # /command 정의
        ├── review.md
        └── adr-sync.md
```

---

## §10. 의도적 비포함 (Out of Scope)

- **함수·메소드·API 시그니처 컨벤션** — `CONVENTIONS-CODE.md` 참조
- **테스트 코드 본문 형식** — `CONVENTIONS-CODE.md` §6 참조
- **SQL 코드 패턴** — `CONVENTIONS-CODE.md` §7 참조
- **Git branch / commit / PR 컨벤션** — `CONVENTIONS-GIT.md` 단일 소스
- **Agent 사용 시점** — 각 `.claude/agents/<agent>.md` 본문

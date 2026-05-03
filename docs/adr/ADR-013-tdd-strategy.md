# ADR-2026-05-03-013: TDD 전략 — Mixed Test-First + ADR→테스트 매트릭스 + Gherkin-flavored JUnit 5

## Status

Accepted (2026-05-03)

## Context

ADR-002/006/008/009/010/011은 동시성·Saga·멱등성·Outbox·Reconciliation에 강한 결정을 내렸으나, **테스트 방법론은 미결정** 상태로 남았다. 현재 테스트 관련 정보는 다음과 같이 산개되어 있다:

- `CLAUDE.md` "Testing Priorities": 동시성 테스트가 필요한 ADR 목록만 나열
- `DECISIONS.md` §향후 적용 영역 §테스트 전략: 도구 후보(Mockito/Testcontainers/JCStress/k6)만 나열, "리서치 후 적용" 영역으로 미결
- `DECISIONS.md` §결정의 한계 §5: 운영 가정 (Testcontainers 사용, k6 1000 TPS 가정 등)
- `.claude/agents/java-reviewer.md`: 테스트 anti-pattern 일부 (`@SpringBootTest` 남용, `Thread.sleep` 등)
- `.claude/commands/review.md`: 동시성 테스트 영역 점검 항목

도구 선택은 사실상 정해졌으나 **방법론(TDD) 채택 여부와 테스트 분류 정책은 결정되지 않았다**. 이 상태로 구현 단계에 진입하면:

1. 동시성·Saga·멱등성처럼 ADR이 강하게 결정한 영역의 테스트가 누락될 위험
2. "어떤 테스트를 어디까지" 기준이 없어 의미 없는 단위 테스트 양산 또는 핵심 시나리오 누락 동시 발생
3. test 작성 시 매번 "어떤 도구 써야 하는가" 즉흥 결정

본 ADR은 이 결정 공백을 닫는다.

## Options Considered

### 축 1 — 방법론

| 옵션 | 판단 |
|---|---|
| A. Strict TDD (모든 코드 test-first) | 인프라 설정·Spring `@Configuration`·DTO mapping 등 비현실적 영역 존재 → 기각 |
| B. Test-after | 동시성·Saga 같은 영역의 사후 작성 시 누락 발생 위험 높음 → 기각 |
| **C. Mixed (Test-first 의무 영역 + 사후 허용 영역 분리)** | 영역별 trade-off에 부합 → 채택 |

### 축 2 — 커버리지 목표

| 옵션 | 판단 |
|---|---|
| A. Strict 80% 라인 커버리지 | 의미 없는 단위 테스트 양산 위험. Spring Configuration 같은 영역까지 강제하면 가치 0 → 기각 |
| B. 영역별 differentiated 목표 | 운영 부담↑, 영역 분류 자체가 결정 부담 |
| **C. "핵심 시나리오 누락 0건" 정성 기준** | ADR이 결정한 시나리오를 테스트로 1:1 매핑 → 채택 |

### 축 3 — 테스트 분류 (5분류)

| 분류 | 도구 | 비고 |
|---|---|---|
| Unit | JUnit 5 + Mockito | 도메인 로직, VO 검증 |
| Slice | `@WebMvcTest`, `@DataJpaTest` | Controller·Repository 격리. `@DataJpaTest`는 Testcontainers MySQL 권장 (H2 거짓 통과 위험) |
| Integration | `@SpringBootTest` + Testcontainers (MySQL + Redis) | Saga·Outbox·Reconciliation 흐름 |
| Concurrency | ExecutorService + CountDownLatch (1차) / JCStress (2차) | ADR-002/006/008 |
| Load | k6 또는 Gatling | 1000 TPS burst 검증 |

### 축 4 — 동시성 도구

| 옵션 | 판단 |
|---|---|
| A. JCStress 단독 | JIT/메모리 visibility 검증에 특화. 학습 곡선↑ |
| B. ExecutorService + CountDownLatch 단독 | oversell·멱등 race 등 일반 race condition 충분 |
| **C. ExecutorService 1차 + JCStress 2차** | 일반 race는 ExecutorService, JIT·재정렬·visibility 의심 시 JCStress → 채택 |

### 축 5 — 통합 환경

| 옵션 | 판단 |
|---|---|
| A. Embedded (H2/Embedded Redis) | MySQL 8.0+ 특화 문법(`ON DUPLICATE KEY UPDATE`, `JSON`)·Redis Lua 차이로 거짓 통과 위험 → 기각 |
| **B. Testcontainers** | 실제 MySQL 8.0+ / Redis 이미지로 ADR과 정합 → 채택 |
| C. Mock 단독 | 동시성·트랜잭션·Lua 검증 불가 → 기각 |

### 축 6 — 외부 시스템 mock

| 옵션 | 판단 |
|---|---|
| A. Mockito mock | HTTP 헤더(Idempotency-Key) 검증 불가, timeout/5xx 시나리오 재현 어려움 → 기각 |
| **B. WireMock** | 실제 HTTP 응답·timeout·fault 시나리오 재현 가능, 헤더 검증 가능 → 채택 |

### 축 7 — BDD framework / 시나리오 표현

| 옵션 | 판단 |
|---|---|
| A. Full Cucumber-JVM (.feature + step defs) | BA/QA 부재 → 협업 가치 0. 결제 도메인에서 사용 사례 부재 (silence as data). 동시성/Outbox 시나리오에 Gherkin DSL 표현력 한계. → 기각 |
| B. Hybrid (happy-path Cucumber + 기술 테스트 JUnit 5) | 두 시스템 운영 부담. 일관성 떨어짐. → 기각 |
| **C. Gherkin-flavored JUnit 5** | feature 파일은 Gherkin Scenario 형식 (시나리오 표현 도구), 테스트는 JUnit 5 + `@DisplayName` + `// Given/When/Then` 주석 + Traceability. 의존성 0, 단일 시스템. → 채택 |

**근거 (research-backed)**: Cucumber 창시자 Aslak Hellesøy의 anti-pattern 목록에 *"BDD without business stakeholders"*가 명시. 본 프로젝트는 BA/QA 없이 단일 dev 팀이 작성·읽음 → 이 anti-pattern에 정확히 해당. 추가로 결제 도메인에서 Cucumber 사용 사례가 industry research에서 발견되지 않음 (negative signal). 동시성 race condition 테스트는 Cucumber Gherkin DSL로 idiomatic하게 표현 불가능.

## Decision

**Mixed Test-First + 정성 기준 + 5분류 + ExecutorService(1차) + JCStress(2차) + Testcontainers + WireMock + Gherkin-flavored JUnit 5**

### Test-first 의무 영역 (Red-Green-Refactor 강제)

- 도메인 로직 (`domain/`) — Aggregate·VO·State Machine
- 멱등성 처리 (`application/`의 booking 진입 흐름)
- Saga 보상 (PG 호출 ↔ DB 트랜잭션 ↔ 보상)
- 동시성 임계 영역 (Lua atomic 호출부, CAS 전이)
- Outbox 컨슈머 idempotency 패턴
- Reconciliation 워커 상태 전이

### Test-after 허용 영역

- Spring `@Configuration` 클래스
- DTO ↔ 도메인 매핑 (검증된 라이브러리 사용 시)
- 외부 SDK boilerplate (PG client wrapper, Redis client config)
- Logging/Metrics 계측

### ADR → 테스트 매핑 매트릭스 (단일 소스)

| ADR | 필수 테스트 영역 | 도구 | 핵심 시나리오 |
|---|---|---|---|
| ADR-002 Lua atomic | Concurrency | ExecutorService + Testcontainers Redis | 동시 1000 진입 시 Lua atomic 보장 |
| ADR-005 Token Bucket | Slice + Concurrency | `@WebMvcTest` + ExecutorService | rate limit 정확도 / 동시 burst 거부 |
| ADR-006 멱등성 | Unit + Integration + Concurrency | Mockito + Testcontainers + ExecutorService | body_hash 일치/불일치 / 3-state 응답 (200/409/422) / 동시 동일 키 차단 |
| ADR-007 Circuit Breaker | Integration | Resilience4j 테스트 유틸 | 50% 임계 OPEN 전이 |
| ADR-008 재고 카운터 | Concurrency | ExecutorService + Testcontainers Redis | oversell/undersell 0건 |
| ADR-009 Saga | Integration | Testcontainers + WireMock(PG) | DB 실패 시 PG 취소 호출 검증 |
| ADR-010 Outbox | Integration | Testcontainers MySQL | at-least-once 보장 + Consumer ROW_COUNT() 분기 (write-only trap 검출 포함) |
| ADR-011 PaymentAttempt/Reconciliation | Unit + Integration | Testcontainers + WireMock(PG) | 상태 머신 / Reconciliation 워커 / Late Success / NOT_FOUND ≠ FAILED |

### Test naming convention

- `should_<expected behavior>_when_<condition>` (snake_case 허용)
- 예: `should_return_409_when_idempotency_key_in_processing`
- weak naming 금지 (`testFindUser`, `test1`) — `java-reviewer.md`와 정합
- `@DisplayName`은 원본 Gherkin Scenario 라벨 그대로 (한글 OK)

### Mocking 정책

- **외부 시스템 (PG)**: WireMock — 실제 HTTP 응답·timeout·5xx 시나리오 재현
- **Redis/MySQL**: Testcontainers — Embedded·mock 금지
- **내부 의존성**: Mockito

### Gherkin-flavored JUnit 5

feature 파일(`docs/features/feature-NNN-*.md`)의 AC 섹션은 Gherkin Scenario 형식으로 작성한다. 시나리오 1개당 JUnit 5 테스트 메소드 1개로 1:1 매핑:

```java
// Scenario: 동시 동일 키 → 1건만 성공, 99건 409
// Source: docs/features/feature-001-idempotency-handling.md
@Test
@DisplayName("동시 동일 키 → 1건만 성공, 99건 409")
void should_block_concurrent_same_key_requests() {
    // Given: ...
    // When: ...
    // Then: ...
}
```

**Cucumber-JVM은 사용하지 않는다** — Gherkin은 시나리오 표현 도구로만 활용. 실제 실행 엔진은 JUnit 5 단일.

### Edge Case 의무 조항

모든 feature는 최소 1개의 `[edge:*]` Scenario를 포함해야 한다.

**근거**:
- 실제 버그는 happy path가 아닌 boundary·failure injection·tampering에서 발생
- happy-path만으로는 ADR이 결정한 핵심 보장(NOT_FOUND ≠ FAILED / oversell 0건 / 이중결제 차단 / at-least-once delivery)이 검증되지 않음
- 사용자 신뢰 손상은 정상 케이스가 아니라 비정상 케이스에서 발생

**예외**:
- 적용 가능한 edge category가 없다고 판단되면 feature 파일에 *"Edge case N/A — reason: ..."* 명시. 단, 도메인 영역(`domain/` / `application/`)에서 N/A는 사실상 불가능하며 검토 대상.

**Enforcement**:
- `tdd-planner` Step 4: Gherkin Scenario 작성 후 ≥1 `[edge:*]` 검증. 누락 시 ADR 매트릭스 row 다시 보고 추가 시나리오 발굴
- `test-author` Operating Procedure: feature 파일에 edge case Scenario 없으면 코드 작성 refuse + tdd-planner에 보강 요청

### Scenario Type Taxonomy

모든 Gherkin Scenario 라벨은 다음 형식: `Scenario: [type] <description>`

| Type label | 의미 | 본 프로젝트 예시 |
|---|---|---|
| `[happy]` | 정상 흐름. 모든 전제 충족 시 기대 결과 | 신규 멱등성 키 → 200 OK |
| `[edge:boundary]` | 수치/시간 임계값. 정확히 N, N+1, 임계값 직전/직후 | 재고 정확히 N개, amount 0, 정확히 N+1번째 거부 |
| `[edge:failure]` | 외부 시스템 장애 / 부분 실패 | PG 5xx, Redis OPEN, DB constraint violation, Outbox INSERT 실패 |
| `[edge:concurrency]` | 동시성 race 검증 | 동시 100건 동일 키, 1000 thread Lua atomic |
| `[edge:tampering]` | 입력 변조 / 잘못된 요청 | body_hash mismatch (422), 음수 amount, 변조된 키 |
| `[edge:expiry]` | 시간 기반 만료 / TTL | TTL 만료 후 재시도, UNKNOWN_TTL boundary |

추가 카테고리가 필요하면 본 ADR amendment로 새 type label 추가.

### Scenario Map 컨벤션

각 feature 파일의 `## Feature` 섹션 Gherkin 블록 직후에 `### Scenario Map` 표를 둔다. 시나리오 ↔ 테스트 메소드 ↔ 상태 1:1 매핑.

```markdown
| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | <Scenario 라벨> | happy/edge:* | `should_X_when_Y` | `Class.java` | pending |
```

**Status enum**: `pending` (시작 전) / `RED` (실패 테스트 작성됨) / `GREEN` (구현 통과) / `done` (Phase 5 review 통과)

**갱신 주체**:
- 초기 채우기: `tdd-planner` Step 5
- Status 전이: 호출자(main claude) — phase 완료 시 Progress Log + Scenario Map 동시 갱신

**프로젝트 전역 dashboard**: `docs/TEST_MATRIX.md` — 모든 feature의 Scenario Map 집계. cross-feature 가시성 + ADR별 edge case coverage audit.

### JUnit 5 `@Tag` 매핑

`test-author`가 테스트 작성 시 Scenario type을 JUnit 5 `@Tag`로 1:N 매핑:

```java
// Scenario: [edge:tampering] 같은 키 + 다른 body → 422
// Source: docs/features/feature-001-idempotency-handling.md
@Test
@Tag("edge")          // 모든 edge case 공통 (필터링용)
@Tag("edge:tampering") // 세부 카테고리
@DisplayName("같은 키 + 다른 body → 422")
void should_return_422_when_body_hash_differs() { /* ... */ }
```

`[happy]` Scenario는 `@Tag("happy")` 단일 부여.

**효과**:
- `mvn test -Dgroups=edge:concurrency` — 동시성 edge case만 실행
- `mvn test -Dgroups=happy` — 빠른 smoke 검증
- `mvn test -DexcludedGroups=edge:concurrency` — CI에서 무거운 동시성 테스트 분리
- IDE에서 tag별 그룹화

### 보상 시나리오 의무 케이스 (Integration)

다음 3개 시나리오는 본 ADR이 의무 테스트로 박는다:

1. **PG 호출 성공 + DB 커밋 실패 → PG 취소 API 호출 검증** (ADR-009)
2. **PG 호출 timeout → booking UNKNOWN + Reconciliation 6분 후 SUCCESS 확정** (ADR-011)
3. **Outbox 폴러 한 이벤트 두 번 발행 → 컨슈머 ROW_COUNT() 분기로 외부 호출 1회 보장** (ADR-010 amendment)

이 3개가 빠지면 본 ADR을 위반한 것으로 본다.

### 운영 — Feature 파일 + agent 위임

- feature 단위 작업은 `docs/features/feature-NNN-*.md` 파일에 누적 (Request / Feature / Execution Plan / Progress Log / Outcome)
- `tdd-planner` agent: feature 파일 생성·계획 채움 (Phase 0~6)
- `test-author` agent: Phase 2 RED — 테스트 코드 작성 (5 Pattern Catalog 내장)
- `code-architect` / `java-reviewer` / `database-reviewer`: 기존 agent 그대로 활용

## Consequences

**긍정 결과**:
- ADR이 결정한 시나리오가 테스트로 1:1 매핑되어 누락 위험 제거
- 라인 커버리지 % 강제 없음 → 의미 없는 단위 테스트 양산 방지
- Testcontainers + WireMock으로 거짓 통과 위험 차단
- Gherkin Scenario로 시나리오 표현력 확보, Cucumber-JVM 의존성 회피로 단일 실행 엔진 유지
- feature 파일 + agent 위임으로 매 구현 cycle의 즉흥 결정 부담 제거

**부정 결과**:
- Testcontainers Docker 의존성 — CI 빌드 시간 증가 (컨테이너 재사용·병렬화로 완화)
- JCStress 학습 곡선 — 일반 동시성 테스트는 ExecutorService로 우회 가능
- "정성 기준"은 누락 검증 책임을 사람(또는 `tdd-planner` agent)에 둠 — 자동 측정 불가
- feature 파일 컨벤션 도입으로 trivial 작업 분리 정책 운영 부담 (의무 영역만 적용으로 완화)

**재검토 시점**:
- CI 빌드 시간이 임계치 초과 시 (Testcontainers 컨테이너 재사용·병렬화 강화 검토)
- 동시성 테스트에서 ExecutorService로 잡지 못하는 race 발견 시 (JCStress 도입 가속)
- 라인 커버리지 측정이 KPI로 요구될 때 (Jacoco 도입 + 정량 보완)
- BA/QA가 합류해 .feature 파일을 직접 읽고 쓸 가능성이 생길 때 (Cucumber-JVM 도입 재평가)

## Out of Scope

- **Mutation testing (PIT)** — 향후 도입. 본 ADR은 기능 시나리오 누락 0건이 1차 목표
- **Contract testing (Pact)** — PG 단일 외부 시스템에 과함. 외부 통신 1개로 비대칭
- **Property-based testing (jqwik)** — 도메인 복잡도가 도달하지 않음. 멱등성 키 같은 영역에 향후 검토
- **라인 커버리지 % 측정** — 정성 기준 채택으로 대체. Jacoco는 KPI 요구 시 추가
- **Cucumber-JVM** — 축 7에서 명시 기각 (BDD without stakeholders)

## 관련 ADR

- ADR-002 / ADR-005 / ADR-006 / ADR-007 / ADR-008 / ADR-009 / ADR-010 / ADR-011 — 본 ADR이 테스트 영역으로 매핑
- DECISIONS.md "결정의 한계 §3 DB 부하 관리" — Testcontainers MySQL 8.0+ 환경 가정
- ERD.md §8 DDL — Testcontainers init script로 활용

## References (research-backed)

- WireMock fault simulation: https://wiremock.org/docs/simulating-faults/
- Spring Boot @Scheduled testing: https://www.baeldung.com/spring-testing-scheduled-annotation
- Java time Clock for test: https://www.javaspring.net/blog/how-to-abstract-away-java-time-clock-for-testing-purposes-in-spring/
- Testcontainers Redis: https://www.baeldung.com/spring-boot-redis-testcontainers
- ShedLock testing: https://www.baeldung.com/shedlock-spring
- Idempotency key patterns: https://www.morling.dev/blog/on-idempotency-keys/
- Outbox pattern: https://microservices.io/patterns/data/transactional-outbox.html
- AWS prescriptive (transactional outbox): https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html
- Cucumber anti-patterns: https://cucumber.io/blog/bdd/cucumber-anti-patterns-part-two/

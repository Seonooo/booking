# Feature NNN: <Feature Name>

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Draft / Planning / In-Progress / Review / Done | <name> | YYYY-MM-DD | YYYY-MM-DD |

> **본 feature 파일은 self-contained로 작성/유지된다.** 모든 컨텍스트(적용 ADR 인용·ERD 엔티티·영향 코드 경로·테스트 패턴 번호)는 파일 내에 명시한다. 다른 세션이 본 파일만 보고 Phase 실행이 가능해야 한다 — *"이전 대화에서 논의한 대로"* 같은 외부 대화 참조 금지. 사용자 메시지 인용이 필요하면 Request 섹션에 그대로 보존.

## Request

(원래 사용자 요청 — 변경 없이 보존)

## Feature

공식 요구사항을 **Gherkin Scenario** 형식으로 작성한다. `test-author` agent가 시나리오 1개당 테스트 메소드 1개로 변환한다.

모든 Scenario 라벨은 `Scenario: [type] <description>` 형식 — type은 `happy` 또는 `edge:CATEGORY` (ADR-013 §Scenario Type Taxonomy 참조).

**type label**:
- `[happy]` — 정상 흐름
- `[edge:boundary]` — 수치/시간 임계값
- `[edge:failure]` — 외부 시스템 장애 / 부분 실패
- `[edge:concurrency]` — 동시성 race 검증
- `[edge:tampering]` — 입력 변조 / 잘못된 요청
- `[edge:expiry]` — 시간 기반 만료 / TTL

**의무**: 모든 feature는 최소 1개의 `[edge:*]` Scenario를 포함해야 한다 (ADR-013 §Edge Case 의무 조항).

```gherkin
Background:
  Given <공통 전제 — 시스템 상태, 데이터 셋업>
  And   <필요 시 추가 전제>

Scenario: [happy] <시나리오 1 한 줄 요약>
  Given <전제>
  And   <추가 전제>
  When  <행위>
  Then  <기대 결과>
  And   <추가 결과>

Scenario: [edge:CATEGORY] <시나리오 2 — boundary/failure/concurrency/tampering/expiry 중 하나>
  ...

Scenario Outline: [happy] <파라미터화된 시나리오, 필요 시>
  Given ...
  When  ...
  Then  ...

  Examples:
    | param | result |
    | a     | X      |
    | b     | Y      |
```

**규칙**:
- Cucumber-JVM은 사용하지 않는다 (ADR-013 §축 7 결정). Gherkin은 *시나리오 표현* 도구로만 활용.
- 각 Scenario에 type label + short description (예: `Scenario: [edge:concurrency] 동시 동일 키 → 1건만 성공, 99건 409`)을 두어 `test-author`가 메소드명·`@DisplayName`·`@Tag`에 매핑.
- Background는 모든 Scenario 공통 전제. 중복 제거.
- 동시성·시간 기반 시나리오도 Gherkin으로 표현 — 수치(`100 동시 요청`)·시각(`6분 전`)을 Given/When 절에 명시.

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | <Scenario 라벨 그대로> | happy / edge:* | `should_X_when_Y` | `<Class>Test.java` | pending |
| 2 | ... | ... | ... | ... | pending |

**Status enum**: `pending` (시작 전) / `RED` (실패 테스트 작성됨) / `GREEN` (구현 통과) / `done` (Phase 5 review 통과). `tdd-planner`가 초기 채움, 호출자(main claude)가 phase 완료 시 갱신.

---

## Execution Plan (TDD)

### Phase 0: Context

- **Applied ADRs**: ADR-XXX, ADR-YYY
- **Test-first 의무 영역**: YES — 이유: ... / NO — 이유: ...
- **영향 엔티티 (`docs/ERD.md`)**: ...
- **기존 패턴 참조**: ...

### Phase 1: Architectural Blueprint

- [ ] pending / [ ] in-progress / [ ] done
- **위임**: `code-architect`
- **요청**: 본 feature의 file path / interface / data flow / build order
- **검증 커맨드**:
  - blueprint 출력의 file path가 `docs/ARCHITECTURE.md` 디렉토리 구조와 정합
  - `git diff --name-only` — 코드 변경 0건 (blueprint은 문서만)
- **결과 (Output)**: <agent 응답·산출 파일 경로 누적>

### Phase 2: RED — Failing Tests

- [ ] pending / [ ] in-progress / [ ] done
- **위임**: `test-author`
- **테스트 파일**:
  - `src/test/java/com/booking/.../FeatureXxxTest.java` (Unit)
  - `src/test/java/com/booking/integration/FeatureXxxIntegrationTest.java` (Integration)
- **시나리오** (ADR-013 매트릭스 §<해당 ADR>): Feature §<Scenario 라벨> ↔ 테스트 메소드 1:1 매핑
- **mocking 경계**: WireMock(PG) / Testcontainers(MySQL+Redis) / Mockito(내부)
- **참고 패턴 (test-author Pattern Catalog)**: Pattern 1 (PG Saga) / Pattern 2 (Reconciliation 시간) / Pattern 3 (Lua 동시성) / Pattern 4 (Outbox idempotency) / Pattern 5 (3-state 응답)
- **검증 커맨드** (모두 실패해야 RED 통과):
  ```bash
  mvn test -Dtest=FeatureXxxTest                          # Unit
  mvn test -Dtest=FeatureXxxIntegrationTest               # Integration
  # 단일 시나리오만 실행:
  mvn test -Dtest=FeatureXxxIntegrationTest#should_X_when_Y
  ```
- **AC**: 작성된 테스트 모두 fail (production 코드 미구현 상태). 컴파일은 성공.
- **결과**: ...

### Phase 3: GREEN — Minimal Implementation

**분할 임계** (ADR-013 §Scope 최소화):
- **≥3 레이어 변경 시 sub-phase 분할 의무** (3.1, 3.2, 3.3, ...). harness §C-1 *"하나의 step에서 하나의 레이어만"* 원칙.
- **1~2 레이어 변경 시 단일 Phase 3 유지** (불필요한 양식 부담 회피).
- 의존 순서 기본: `Domain → Application → Infrastructure → API`. 다른 순서 필요 시 sub-phase 본문에 의존 그래프 명시.

#### 단일 Phase 3 형식 (1~2 레이어)

- [ ] pending / [ ] in-progress / [ ] done
- **위임**: 호출자 (main claude) 또는 `code-architect` 가이드 후 implementer
- **작성 대상**: 변경 레이어 클래스/파일 목록
- **CRITICAL 제약** (CLAUDE.md):
  - PG 호출은 DB 트랜잭션 밖
  - Redis 원자 연산은 Lua Script
  - Outbox INSERT 실패는 fallback 로깅 (롤백 X)
- **검증 커맨드** (Phase 2와 동일 — 이번엔 모두 통과):
  ```bash
  mvn test -Dtest=FeatureXxxTest
  mvn test -Dtest=FeatureXxxIntegrationTest
  ```
- **AC**: Phase 2에서 작성한 테스트 모두 통과. 기존 다른 테스트는 깨지지 않음 (`mvn test` 전체).
- **결과**: ...

#### Sub-phase 분할 형식 (≥3 레이어, 단일 Phase 3 대신 사용)

각 sub-phase는 다음 항목 필수:

```markdown
#### Phase 3.N: <레이어명> GREEN

- [ ] pending / [ ] in-progress / [ ] done
- **작성 대상**: 해당 레이어 클래스/파일 목록
- **CRITICAL 제약**: 해당 레이어 특유 불변 (예: Domain — 불변식 검증 / Application — 트랜잭션 경계 / Infrastructure — Lua atomic / API — `@Valid`)
- **검증 커맨드**:
  ```bash
  mvn test -Dtest=<레이어 단위 테스트>
  ```
- **AC**: 해당 레이어 단위 테스트 GREEN. 다음 sub-phase 의존 레이어는 RED 유지 가능 (마지막 sub-phase 완료 시 Integration GREEN).
- **결과**: ...
```

마지막 sub-phase는 통합 테스트 GREEN 검증 포함:

```markdown
#### Phase 3.N (마지막): API + Integration GREEN

- **검증 커맨드**:
  ```bash
  mvn test -Dtest=FeatureXxxIntegrationTest
  mvn test                                                # 기존 테스트 미파괴
  ```
- **AC**: Phase 2 RED 시나리오 모두 GREEN. 기존 테스트 미파괴.
```

### Phase 4: REFACTOR

- [ ] pending / [ ] in-progress / [ ] done
- **위임**: `code-architect` (구조적 변경 시) / 호출자 (지엽적)
- **검토 포인트**: 결합도, 중복, 이름, 테스트 통과 유지
- **검증 커맨드**:
  ```bash
  mvn test                                                # 전체 테스트 통과 유지
  ```
- **AC**: 모든 기존 테스트 GREEN 유지. 새 테스트 추가는 본 phase에서 금지 (refactor only).
- **결과**: ...

### Phase 5: Review

- [ ] pending / [ ] in-progress / [ ] done
- **위임 (병렬 가능)**:
  - `java-reviewer` — Spring Boot 패턴, 트랜잭션 경계, 동시성 anti-patterns
  - `database-reviewer` — DDL·인덱스·쿼리 (DB 변경 있을 때)
- **검증 커맨드**:
  ```bash
  mvn verify                                              # 전체 통합 (test + integration test profile)
  git diff main...HEAD                                    # agent 리뷰 대상 diff
  ```
- **AC**: agent 리뷰에서 CRITICAL/HIGH 0건. MEDIUM 이하는 본 feature 범위 내 처리 또는 Out-of-scope 명시.
- **결과**: ...

### Phase 6: Concurrency / Load Verification (해당 시)

- [ ] pending / [ ] in-progress / [ ] done
- **트리거 조건**: ADR-002/005/006/008 영역
- **위임**: `test-author` (Pattern 3 / Pattern 5) / k6 스크립트는 `load-test/` 디렉토리
- **도구**: ExecutorService + Testcontainers (1차) / JCStress (2차) / k6 (load)
- **검증 대상 메트릭**: oversell 0건 / 멱등 race 0건 / Lua atomic 정확도
- **검증 커맨드**:
  ```bash
  mvn test -Dgroups=edge:concurrency                      # 동시성 edge case 전체
  mvn test -Dtest=FeatureXxxConcurrencyTest               # 본 feature 동시성만
  k6 run load-test/<feature>.js                           # Load test (해당 시)
  ```
- **AC**: 동시성 시나리오 통과 + 메트릭 임계 만족 (예: oversell 0건, success count == stock).
- **결과**: ...

---

## Progress Log

(append-only — phase 완료 시 한 줄씩 추가)

- YYYY-MM-DD HH:MM — Plan populated by `tdd-planner` (covered ADRs: ADR-XXX, ADR-YYY)
- YYYY-MM-DD HH:MM — Phase 1 done by `code-architect` (output: src/main/java/...)
- YYYY-MM-DD HH:MM — Phase 2 done by `test-author` (files: src/test/java/...)
- ...

---

## Outcome (feature Done 시 채움)

- **Files created/modified**:
  - `src/main/java/...` 목록
  - `src/test/java/...` 목록
- **Tests added** (분류별):
  - Unit: <개수> — 메소드명 또는 클래스 단위 요약
  - Slice: ...
  - Integration: ...
  - Concurrency: ...
- **ADR validation**: ADR-XXX의 시나리오 모두 테스트로 매핑됨 (매트릭스 row 모두 cover됐는지 확인)
- **Follow-up**: (있다면 다음 feature 또는 ADR로 이관)

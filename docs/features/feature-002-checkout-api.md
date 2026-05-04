# Feature 002: GET /checkout — 주문서 조회 API (REQUIREMENTS §1.1)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

> **본 feature 파일은 self-contained로 작성/유지된다.** 모든 컨텍스트(REQUIREMENTS / ERD 엔티티 / 영향 코드 경로)는 파일 내에 명시.
>
> **본 feature 는 *minimal MVP* 로 진행된다** (사용자 *"빠르게 작업"* 의향). `tdd-planner` / `code-architect` agent 거치지 않고 main claude 가 Phase 0/1 인라인 + Phase 3 단일 GREEN. ADR-013 §의무 영역 외 (read-only GET API).

## Request

> REQUIREMENTS.md §1.1 — *"GET Checkout API (주문서 진입): 상품 정보(명칭, 가격, 입/퇴실 시간 등) 및 사용자의 가용 포인트 등을 조회하는 API"*. 본 minimal MVP 는 상품 조회 + 가용 포인트 placeholder 응답.

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태이고
  And   accommodation(id=42, name="테스트 숙소", base_price=50000)이 존재한다

Scenario: [happy] 존재하는 상품 조회 → 200 + 상품 정보 + 가용 포인트
  Given 상품 42가 DB에 존재하고
  When  사용자가 GET /checkout?productId=42 를 호출하면
  Then  HTTP 200 응답을 받고
  And   응답 본문에 productId / name / basePrice / checkInTime / checkOutTime / availablePoints 필드가 포함되며
  And   productId == 42, name == "테스트 숙소", basePrice == "50000.00" 이다

Scenario: [edge:tampering] 존재하지 않는 상품 조회 → 404
  Given 상품 99 가 DB에 존재하지 않을 때
  When  사용자가 GET /checkout?productId=99 를 호출하면
  Then  HTTP 404 응답을 받는다
```

### Scenario Map

| # | Scenario | Type | Test Method | File | Status |
|---|---|---|---|---|---|
| 1 | 존재하는 상품 조회 → 200 | happy | `should_return_200_with_accommodation_when_product_exists` | `CheckoutIntegrationTest.java` | pending |
| 2 | 존재하지 않는 상품 → 404 | edge:tampering | `should_return_404_when_product_does_not_exist` | `CheckoutIntegrationTest.java` | pending |

**Edge case coverage**: 1/2 (50%) — `[edge:tampering]`. ADR-013 §Edge Case 의무 조항 충족.

---

## Execution Plan (TDD — minimal MVP)

### Phase 0: Context

- **Applied**: REQUIREMENTS §1.1 (필수 구현 API). ADR 직접 인용 없음 — read-only 조회.
- **Test-first 의무 영역**: NO — read-only GET API, ADR-013 §의무 영역 외 (사후 허용).
- **영향 엔티티 (`docs/ERD.md`)**: `accommodation` (§4.7 supporting) — 본 PR 에 *checkInTime / checkOutTime* 컬럼 추가 X (placeholder 응답으로 단순화). 가용 포인트 — `point_ledger` 미구현 (ERD 범위 밖) → placeholder 0 반환.
- **기존 패턴 참조**: feature-001 의 `IdempotencyKeyJpaEntity` / `RepositoryAdapter` 패턴 (entity ↔ domain 양방향 변환, `@Component` adapter implements domain port). 단 본 feature 는 *조회 only* — `findById` / `existsById` 만.

### Phase 1: Architectural Blueprint (인라인)

#### 1. 데이터 모델

- `Accommodation` (`com.booking.domain.accommodation`) — immutable record:
  - id (Long), name (String), basePrice (BigDecimal), createdAt (Instant)
- `AccommodationRepository` (driven port, `domain/accommodation/`) — `Optional<Accommodation> findById(long id)` 단일 메소드.
- `AccommodationJpaEntity` (`infrastructure/persistence/`) — `@Entity`, `@Table(name = "accommodation")`. ERD §4.7 컬럼 매핑.
- `AccommodationJpaRepository` extends `JpaRepository<AccommodationJpaEntity, Long>`.
- `AccommodationRepositoryAdapter` implements `AccommodationRepository` (`@Component`).

#### 2. Application

- `CheckoutService` (`application/`) — `CheckoutResponse get(long productId, long userId)`:
  - `accommodationRepository.findById(productId).orElseThrow(AccommodationNotFoundException::new)`
  - 가용 포인트 조회 — placeholder 0 (point 도메인 future feature)
  - `checkInTime` / `checkOutTime` placeholder ("15:00" / "11:00" hardcoded — `Accommodation` 엔티티 컬럼 추가 회피)
- `AccommodationNotFoundException` — RuntimeException → 404 매핑

#### 3. API

- `CheckoutController` (`api/checkout/`) — `@GetMapping`:
  ```java
  GET /checkout?productId=<long>&userId=<long>
  ```
  - `userId` query param 또는 header (인증 외부 게이트웨이 가정 — REQUIREMENTS §5)
- `CheckoutResponse` (record) — `productId`, `name`, `basePrice`, `checkInTime`, `checkOutTime`, `availablePoints`
- `GlobalExceptionHandler` 에 `AccommodationNotFoundException` → 404 추가 — 단 본 feature 는 main 기반이라 GlobalExceptionHandler 미존재 (PR #10 영역). **자체 minimal handler 또는 Controller-level `@ExceptionHandler` 사용**.

#### 4. Test

- `CheckoutIntegrationTest` (`src/test/java/com/booking/integration/`) extends `IntegrationTestSupport` (main 버전 — fixture seed 미포함).
  - `@BeforeEach` 자체 — `accommodation(42)` INSERT (test fixture).
  - 2 시나리오 검증.

### Phase 3: GREEN (단일 phase — 1 layer 가 아닌 다층이지만 minimal 묶음)

- [x] done
- **작성 대상**: 위 Phase 1 항목 8 파일 + Test 1 파일.
- **CRITICAL 제약**: 본 feature read-only — 트랜잭션 / 결제 / 멱등성 무관.
- **검증 커맨드**:
  ```bash
  ./gradlew test --tests "com.booking.integration.CheckoutIntegrationTest"
  ./gradlew test    # 기존 테스트 미파괴
  ```
- **AC**: Scenario 1, 2 모두 GREEN. 기존 테스트 미파괴.
- **결과**:
  - **신규 파일 (10)**:
    - `domain/accommodation/Accommodation` (record) + `AccommodationRepository` (port)
    - `infrastructure/persistence/AccommodationJpaEntity` + `AccommodationJpaRepository` + `AccommodationRepositoryAdapter`
    - `application/CheckoutService` + `AccommodationNotFoundException`
    - `api/checkout/CheckoutController` (+ Controller-local `@ExceptionHandler` for 404) + `dto/CheckoutResponse`
    - `src/test/java/com/booking/integration/CheckoutIntegrationTest` (standalone Testcontainers — main 에 `IntegrationTestSupport` 미존재라 직접 setup)
  - **공동 출시 (feature-001 PR과 동일 내용)**:
    - `db/migration/V1__init.sql` — feat-idempotency-handling branch 에서 verbatim 복사. 두 PR 동일 내용 → merge 시 자동 처리
    - `application-test.yml` `ddl-auto` validate→none — Hibernate 6 매핑 fail 회피, Flyway 단일 schema 출처
  - **검증**: `./gradlew test --tests "com.booking.integration.CheckoutIntegrationTest"` BUILD SUCCESSFUL (2/2 GREEN). `./gradlew test` BUILD SUCCESSFUL (전체 미파괴).
  - **placeholder 책임**: `checkInTime` / `checkOutTime` 하드코딩, `availablePoints` 0 — 후속 feature 에서 본격화.

### Phase 4 / 5 / 6 — Skip (minimal MVP)

- Phase 4 REFACTOR — 후속 feature 진입 시점에 같이.
- Phase 5 Review — 본 PR 의 review skill / agent 호출로 대체 (ADR-013 §의무 영역 외).
- Phase 6 Concurrency — 본 feature read-only, race 영역 없음.

---

## Progress Log

(append-only)

- 2026-05-04 — Feature file 작성 (main claude 직접, tdd-planner skip — minimal MVP). Phase 0/1 인라인.
- 2026-05-04 — Phase 3 (GREEN) done — Accommodation 도메인 + JPA adapter + CheckoutService + Controller + Integration test 2/2 GREEN. Status: In-Progress → Review (PR review 단계 진입).

---

## Outcome (feature Done 시 채움)

- **Files created**:
  - `src/main/java/com/booking/domain/accommodation/Accommodation.java`
  - `src/main/java/com/booking/domain/accommodation/AccommodationRepository.java`
  - `src/main/java/com/booking/infrastructure/persistence/AccommodationJpaEntity.java`
  - `src/main/java/com/booking/infrastructure/persistence/AccommodationJpaRepository.java`
  - `src/main/java/com/booking/infrastructure/persistence/AccommodationRepositoryAdapter.java`
  - `src/main/java/com/booking/application/CheckoutService.java`
  - `src/main/java/com/booking/application/AccommodationNotFoundException.java`
  - `src/main/java/com/booking/api/checkout/CheckoutController.java`
  - `src/main/java/com/booking/api/checkout/dto/CheckoutResponse.java`
  - `src/test/java/com/booking/integration/CheckoutIntegrationTest.java`
  - **공동 출시 (feature-001 PR 동일)**: `src/main/resources/db/migration/V1__init.sql`, `src/main/resources/application-test.yml` (ddl-auto 정정)
- **Tests added**:
  - Integration: 2 (happy 1 + edge:tampering 1)
- **REQUIREMENTS validation**: §1.1 GET Checkout API minimal 충족 — 상품 조회 + 가용 포인트 placeholder.
- **Follow-up**:
  - `point_ledger` 도메인 도입 시 `availablePoints` 실제 조회로 전환
  - `accommodation` 컬럼 (`check_in_time` / `check_out_time`) 본격 추가 시 placeholder 제거 (V2 마이그레이션)
  - feature-001 PR 머지 후 Controller-local `@ExceptionHandler` → `GlobalExceptionHandler` 통합 검토
  - Phase 5 Review (`java-reviewer` agent) — 본 PR review 단계에서 호출 가능

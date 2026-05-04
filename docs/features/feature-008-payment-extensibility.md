# Feature 008: Y페이 + 포인트 결제 확장 (REQUIREMENTS Req 4 / ADR-009)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Review | TBD | 2026-05-04 | 2026-05-04 |

## Request

> 사용자 *"권장옵션으로 처리"* — 옵션 A (Y페이 + 포인트 동시) + PointPayment Mock (point_ledger 도메인 미진입). REQUIREMENTS §1.2 명시 결제 수단 *신용카드, Y페이, Y포인트* + 복합 결제 *(신용카드+포인트) / (Y페이+포인트)*.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `REQUIREMENTS.md` §1.2 / Req 4 | 신용카드 / Y페이 / Y포인트 + 복합 결제 + 카드↔Y페이 혼용 불가 + 신규 결제 추가 시 *비즈니스 로직 수정 최소화* |
| `ADR-009` §클래스 계층 | ExternalPaymentMethod (CARD/YPAY) / InternalPaymentMethod (POINT) 두 계층 Strategy |
| `ADR-009` §Domain 검증 | PaymentComposition 의 *외부 결제 1개 초과 불가* invariant — 카드↔Y페이 혼용 자동 차단 |
| `ADR-009` §4-4 OCP 측정 | 신규 결제 = 신규 파일 1개 + BookingService 변경 0 (본 PR 의 OCP 가설 검증) |

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태
  And   accommodation(id=42, base_price=50000)
  And   stock=10

Scenario: [happy] CARD 결제 → 기존 흐름 회귀
  Given paymentMethod=CARD, amount=50000, points=0
  When  POST /booking
  Then  HTTP 200, booking COMPLETED, PG /payment 호출 1회

Scenario: [happy] YPAY 결제 → YPayPayment.execute
  Given paymentMethod=YPAY, amount=50000, points=0
  When  POST /booking
  Then  HTTP 200, booking COMPLETED, PG /ypay/payment 호출 1회

Scenario: [happy] CARD + POINT 복합 결제 → PG 외부 + 포인트 내부
  Given paymentMethod=CARD, amount=50000, points=5000 (총 결제 50000 중 5000 포인트 사용)
  When  POST /booking
  Then  HTTP 200, booking COMPLETED, PG /payment 호출 1회 (amount=45000),
        PointPayment.execute 호출 1회 (amount=5000)

Scenario: [happy] YPAY + POINT 복합 결제
  Given paymentMethod=YPAY, amount=50000, points=10000
  When  POST /booking
  Then  HTTP 200, PG /ypay/payment 호출 1회 (amount=40000), PointPayment 호출 1회 (10000)

Scenario: [edge:tampering] 지원하지 않는 결제 수단 → 400
  Given paymentMethod=UNKNOWN_METHOD
  When  POST /booking
  Then  HTTP 400, 메시지 "지원하지 않는 결제 수단"
```

### Scenario Map

| # | Type | Test Method | File | Status |
|---|---|---|---|---|
| 1 | happy | `should_process_card_payment` | `PaymentExtensibilityIntegrationTest` | GREEN |
| 2 | happy | `should_process_ypay_payment` | `PaymentExtensibilityIntegrationTest` | GREEN |
| 3 | happy | `should_process_card_plus_point_composition` | `PaymentExtensibilityIntegrationTest` | GREEN |
| 4 | happy | `should_process_ypay_plus_point_composition` | `PaymentExtensibilityIntegrationTest` | GREEN |
| 5 | edge:tampering | `should_return_400_when_payment_method_unsupported` | `PaymentExtensibilityIntegrationTest` | GREEN |

**Edge case coverage**: 1/5 — 단 본 feature 의 핵심 = *결제 확장성 + OCP 검증* 이라 happy path 위주. ADR-013 §의무 영역 외 (확장성 검증).

## 핵심 결정 (옵션 A 권장)

| 영역 | 결정 |
|---|---|
| 추가 결제 수단 | Y페이 + 포인트 동시 |
| PointPayment 구현 | **Mock** — `log.info` only. point_ledger 도메인 미진입 (ERD §2.2 out-of-scope). future feature 에서 실제 차감 |
| YPayPayment 구현 | CardPayment 패턴 차용 (RestTemplate POST /ypay/payment). 4XX/5XX/timeout 분기 동일. cancel + queryStatus 는 본 PR 미사용 (throw UnsupportedOperationException) |
| amount / points 의미 | `amount` = 총 결제, `points` = 포인트 사용 금액. external amount = amount - points |
| BookingService OCP | `Map<String, PaymentMethod> paymentMethodsByType` 주입 — 새 결제 수단 추가 시 BookingService 변경 X (Map 자동 lookup) |
| 카드 ↔ Y페이 혼용 검증 | PaymentComposition 의 *외부 1개 초과 불가* invariant 자동 처리. paymentMethod String 단일 필드라 사용자 측 invalid 입력 불가 |

## Phase

| Phase | 작업 |
|---|---|
| 3.1 | YPayPayment + PointPayment + PaymentComposition.executeInternal |
| 3.2 | BookingService 변경 — Map lookup + buildPaymentMethods + executeInternal 호출 |
| 3.3 | Test 5 시나리오 GREEN |

## Out of Scope

- point_ledger 도메인 / 포인트 잔액 검증 — future
- Y페이 cancel + queryStatus 본격 — Saga 보상 / Reconciliation 영역에 통합 (future)
- 카카오페이 등 신규 외부 결제 — feature-008 의 OCP 가설 검증 후 추가 시 신규 파일 1개로 가능
- amount / points 비즈니스 정책 (한도, 적립 등) — 비즈니스 도메인 영역 (DECISIONS.md §결정의 한계 §7)

## Progress Log

- 2026-05-04 — Plan populated. PointPayment Mock 결정. OCP — Map<String, PaymentMethod> lookup 으로 BookingService 변경 0.
- 2026-05-04 — Phase 3.1 GREEN — YPayPayment (CardPayment 패턴) + PointPayment (Mock log) + PaymentComposition.executeInternal.
- 2026-05-04 — Phase 3.2 GREEN — BookingService Map lookup + buildPaymentMethods + executeInternal 호출.
- 2026-05-04 — Phase 3.3 GREEN — PaymentExtensibilityIntegrationTest 5 시나리오 + 전체 ./gradlew test BUILD SUCCESSFUL (회귀 0).

# REQUIREMENTS.md — 시스템 요구사항 단일 소스

> 본 문서는 booking 시스템의 요구사항을 repo 내 단일 진실의 원천으로 박는다.
> ADR / `DECISIONS.md` / feature 파일이 본 요구사항을 참조하며, 변경 시 본 파일을 우선 수정한다.
> 요구사항 ↔ ADR coverage 점검은 `adr-sync` skill의 의무 점검 항목 (Gap A/B 참조).

---

## 1. 구현 대상 API

> 요청/응답 규격은 본 시스템 결정에 따른다.

### 1.1 GET Checkout API (주문서 진입)

상품 정보(명칭, 가격, 입/퇴실 시간 등) 및 사용자의 가용 포인트 등을 조회.

### 1.2 POST Booking API (결제 및 예약 완료)

주문서 정보를 입력받아 결제를 진행하고 최종 주문을 생성.

---

## 2. 핵심 요구 사항

### Req 1. 재고 정합성 및 공정성

- **정합성**: 00시 트래픽 집중 상황에서 미달/초과판매 **0건** 보장.
- **공정성**: 모든 사용자가 동등한 확률로 상품을 구매할 수 있는 구조.

### Req 2. 고가용성

- TPS 급증 대응 — 시스템 붕괴 방지 구조 반영.
- **`DECISIONS.md` 기술 항목**.
- **TPS 프로파일**:
  - 평시: **50 TPS**
  - 자정 프로모션 burst: **1~5분간 500~1000 TPS**

### Req 3. 멱등성 처리

주문서에서 짧은 간격으로 연속 결제 요청 시 중복 처리 방지.

### Req 4. 결제 확장성

- 결제 수단: **신용카드, Y페이, Y포인트**.
- 복합 결제: **(신용카드 + 포인트)** 또는 **(Y페이 + 포인트)**.
- 제약: **신용카드 ↔ Y페이 혼용 불가**.
- 새 결제 수단 추가 시 Booking API 비즈니스 로직 수정 **최소화** 구조 + **`DECISIONS.md` 상세 기술 항목**.

### Req 5. 장애 대응 및 예외 처리

- **Redis 장애**: Fallback 전략 + 근거 + **`DECISIONS.md` 상세 기술 항목**.
- **결제 실패** (한도 초과 등): 대응 로직 설계 + **`DECISIONS.md` 상세 기술 항목**.

---

## 3. 요구사항 ↔ ADR / DECISIONS.md 매핑

| Req | 항목 | 메커니즘 (ADR) | `DECISIONS.md` §X | 상태 |
|---|---|---|---|---|
| 1 | 정합성 | ADR-008 (재고 카운터) + ADR-002 (Lua atomic) | §2 진입 통제 | ✅ |
| 1 | 공정성 | ADR-004 (정의) + ADR-005 (Rate Limit) | §1 공정성 정의 | ✅ |
| 2 | 고가용성 | ADR-007 + ADR-005 + ADR-008 + 결정의 한계 §1 | §5 Redis 장애 방어만 | ⚠ **Gap A** |
| 3 | 멱등성 | ADR-006 (Redis SETNX + DB UNIQUE) | §4 멱등성 | ✅ |
| 4 | 결제 확장성 | ADR-009 (두 계층 Strategy + Saga) | §3 결제 확장성 | ✅ |
| 4 | 카드↔Y페이 혼용 불가 | ADR-009 `PaymentComposition` Domain VO | §3 (간접) | ✅ |
| 5 | Redis 장애 fallback | ADR-007 (Sentinel + Resilience4j + Fail-Closed) | §5 Redis 장애 방어 | ✅ |
| 5 | 결제 실패 (한도 초과) | ADR-009 Saga 보상 + ADR-008 재고 복구 | §3 한 줄 (Saga만) | ⚠ **Gap B** |

---

## 4. Open Items (`DECISIONS.md` 보강 필요)

본 요구사항은 **"DECISIONS.md에 기술 / 상세 기술"** 을 명시. 메커니즘은 ADR에 있으나 `DECISIONS.md` 종합 섹션이 부족한 영역:

### Gap A — 고가용성 종합 섹션 부재 (Req 2)

- 메커니즘은 ADR-007/005/008 + 결정의 한계 §1에 분산.
- `DECISIONS.md` 핵심 결정 §10 (또는 §5 확장) 신설 필요:
  - 다층 방어 (Rate Limit → 재고 카운터 → Bulkhead → Circuit Breaker → Fail-Closed)
  - TPS burst 흡수 경로
  - 인스턴스 수평 확장 가정
- 시스템 환경 요약의 *"1000 TPS burst"* 를 *"500~1000 TPS burst"* 로 정정.

### Gap B — 결제 실패 대응 흐름 미정리 (Req 5)

- ADR-009 Saga에 흐름은 있으나, `DECISIONS.md` §3에는 *"DB 실패 시 PG 취소"* 한 줄뿐.
- `DECISIONS.md` 핵심 결정 §11 신설 필요:
  - 결제 실패 분류 3종: PG 거절(한도/거절) / Saga 보상(DB 실패) / Timeout 미결(ADR-011)
  - 각각의 재고 복구 / 멱등성 / 사용자 응답 흐름

---

## 5. Out of Mandatory Scope

본 요구사항에 명시되지 않은 영역 (구현 의무 없음):

- **Cancel API / 환불 도메인** — ADR-012 (Planned). `cancellation_intent` 테이블·`REFUND_PENDING` 상태는 ERD에 미래 스키마로 박혀 있으나 forward path 코드는 진입 안 함.
- 회원/인증/프로필 도메인.
- 회계/정산 도메인 (ADR `결정의 한계` §8).
- 관측성 / 운영 절차 / Runbook (별도 영역).

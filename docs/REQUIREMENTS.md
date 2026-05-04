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
- **수량**: 자정 오픈 burst 윈도우 동안 단일 상품군 **10개 한정** (`docs/adr/DECISIONS.md` §시스템 환경 요약 / `docs/ARCHITECTURE.md` §1 정합).

### Req 2. 고가용성

- TPS 급증 대응 — 시스템 붕괴 방지 구조 반영.
- **`DECISIONS.md` 기술 항목**.
- **TPS 프로파일**:
  - 평시: **50 TPS**
  - 자정 burst (관측): 1~5분간 **500~1000 TPS 변동**
  - 설계 capacity 기준: **1000 TPS 상한** (모든 race / circuit breaker / pool sizing 분석은 이 상한 기준)

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

> **Status: Resolved.** Gap A·B 모두 `docs/adr/DECISIONS.md` §10 / §11 신설로 해소됨. 본 섹션은 추적용 기록으로 유지한다.

### Gap A — 고가용성 종합 섹션 (Req 2) — ✅ Resolved

- **메커니즘 출처**: ADR-007 (Sentinel + Resilience4j + Fail-Closed) / ADR-005 (Rate Limit) / ADR-008 (재고 카운터) + 결정의 한계 §1 (수평 확장)
- **Resolution**: `docs/adr/DECISIONS.md` §10 *고가용성 다층 방어* 신설
  - 4층 방어 시퀀스: Rate Limit → 재고 카운터 → Bulkhead → Circuit Breaker → Fail-Closed
  - 인프라 보조: Sentinel HA / 인스턴스 4~5대 수평 확장 / ShedLock 분산 락
  - TPS burst 흡수 경로 (1000 TPS 상한 → 재고 10개 → 503)
  - 재검토 트리거 (평시 TPS 200+ / 인스턴스 10대+ / 서킷 OPEN 빈도)
- **TPS 표현 정합** (별개 항목): 시스템 환경 요약의 *"1000 TPS burst"* 표현은 **관측 환경 (500~1000 TPS 변동) + 설계 capacity (상한 1000) 두 차원으로 분리** 명시. `docs/adr/DECISIONS.md` / `docs/ARCHITECTURE.md` / `.claude/agents/` Project Context 동기화 완료 (PR #3).

### Gap B — 결제 실패 흐름 (Req 5) — ✅ Resolved

- **메커니즘 출처**: ADR-009 (Saga 보상) / ADR-010 (Outbox 재시도) / ADR-011 (Reconciliation Worker)
- **Resolution**: `docs/adr/DECISIONS.md` §11 *결제 실패 분류와 보상 흐름* 신설
  - 3종 분류: 케이스 1 PG 거절 / 케이스 2 PG Timeout (UNKNOWN) / 케이스 3 DB 커밋 실패
  - 각 케이스별 재고 처리 / 멱등성 영역 / HTTP 응답 / 보상 책임
  - 응답 / 재고 / 보상 매핑 표 (실패 3종 + 참조 4종)

---

## 5. Out of Mandatory Scope

본 요구사항에 명시되지 않은 영역 (구현 의무 없음):

- **Cancel API / 환불 도메인** — ADR-012 (Planned). `cancellation_intent` 테이블·`REFUND_PENDING` 상태는 ERD에 미래 스키마로 박혀 있으나 forward path 코드는 진입 안 함.
- 회원/인증/프로필 도메인.
- 회계/정산 도메인 (ADR `결정의 한계` §8).
- 관측성 / 운영 절차 / Runbook (별도 영역).

# ADR-2026-05-02-011: PG 멱등성 키 전략 + Timeout Reconciliation

## Status

Accepted — ADR-008/009의 "미결정 영역 — PG 응답 timeout 처리"를 완결한다.

## Context

ADR-009의 PG-first Saga 구조는 유효하지만, 두 가지 결정이 빠져 있다.

**첫째**, PG 호출 시 멱등성 키 전달 전략이 없다. ADR-006의 `idempotency_key`는 우리 API 레벨 중복 방지를 위한 키이며, PG 호출 시 그대로 쓰면 카드 변경 재시도 시 PG가 동일 요청으로 간주해 결제를 차단한다. "retry인가 새 시도인가"를 도메인이 명시적으로 구분해야 한다.

**둘째**, PG 호출이 timeout되어 응답을 받지 못한 경우의 상태 확정 절차가 없다. ADR-008은 "PG 유예 60초 후 강제 회수 + PG 취소 요청"을 결정했으나, 이는 PG가 응답을 보낸 시나리오만 다룬다. PG 호출 자체가 timeout된 경우 즉시 FAILED 처리하면 "청구됨 + 주문 없음" 최악 케이스가 발생한다. ADR-009는 이 시나리오를 "범위 밖"으로 명시했다.

본 ADR은 그 두 결정을 닫는다.

## Options Considered

### 축 1 — PG 호출 시 idempotency key 전달 전략

| 옵션 | 설명 | 판단 |
|---|---|---|
| A. bookingId 직접 사용 | 결제 시도 개념과 혼재, 카드 변경 재결제 시 구분 불가 | 기각 |
| B. ADR-006 idempotency_key 그대로 | 카드 변경 재시도 시 PG가 동일 요청으로 간주 → 결제 차단 | 기각 |
| **C. PaymentAttempt Entity 도입** | retry / 새 시도를 도메인이 명시적으로 구분. UUID per attempt를 PG 멱등성 헤더로 전달 | **채택** |

### 축 2 — PG timeout 후 상태 확정 전략

| 옵션 | 설명 | 판단 |
|---|---|---|
| A. 동기 재시도 | 요청 처리 중 재시도 → 응답 지연, 1000 TPS 스레드 점유 | 기각 |
| **B. 비동기 Reconciliation 워커** | ADR-010 Outbox 폴러와 동일한 @Scheduled + ShedLock 패턴 | **채택** |
| C. PG 웹훅만 수신 | 네트워크 장애 시 웹훅도 미도달 가능 → 폴러 fallback 필수 | 보조 수단으로만 |

## Decision

### 결정 1 — PaymentAttempt 도입

`PaymentAttempt`를 도메인 Entity로 승격한다.

```
PaymentAttempt
  - attemptId (UUID, PG 멱등성 헤더로 전달)
  - bookingId
  - amount + paymentCompositionSnapshot  ← 불변 (생성 시 확정)
  - status: INIT → REQUESTED → ACKED → SUCCESS / FAILED
                              └─ TIMEOUT (응답 미수신)
  - externalPaymentId (nullable, ACKED 이후 저장 — PG 상태 조회 기준)
```

**retry**: 기존 `PaymentAttempt` 동일 `attemptId` 재사용. 동시 진입 차단:
```sql
UPDATE payment_attempt SET status = 'REQUESTED'
WHERE id = ? AND status IN ('INIT', 'TIMEOUT')
-- rows_affected == 0이면 이미 진행 중 → skip
```

**새 결제 의도** (카드 변경, 금액 변경): 새 `PaymentAttempt` 생성.

모든 `ExternalPaymentMethod` 구현체는 `attemptId`를 PG 멱등성 헤더로 전달한다.

**⚠️ payload 불변 원칙**: 동일 `attemptId`에 amount·PaymentComposition 변경 금지. PG 입장에서 "같은 요청인데 payload 다름"은 undefined behavior. 변경 필요 시 새 `PaymentAttempt` 생성.

**⚠️ SENT 상태 미정의**: TCP ACK 수준 상태는 HTTP 클라이언트 애플리케이션 레벨에서 정확히 알 수 없다. "요청이 나갔는지" 판단은 idempotency + Reconciliation으로 해결한다.

---

### 결정 2 — Reconciliation 워커

ADR-010 Outbox 폴러와 동일한 `@Scheduled + ShedLock` 패턴으로 Reconciliation 워커를 구현한다.

**트리거** (2단계):
```
1단계 — DB 후보 조회 (단순하게 유지):
  WHERE booking.status = 'PG_PENDING'
    AND booking.updated_at < now() - INTERVAL '6 minutes'

2단계 — 코드 레벨 guard:
  - attempt.status NOT IN ('TIMEOUT', 'REQUESTED') → skip
  - attempt.last_requested_at < 30초 이내 → skip (in-flight 보호)
  - attempt.last_reconcile_at < 30초 이내 → skip (중복 실행 방지)
```

DB WHERE 조건을 과도하게 복잡하게 만들면 `null` 필드나 clock skew로 "영원히 안 잡히는 케이스"가 발생한다. 후보군 선별은 단순하게, 세부 guard는 코드 레벨에서 처리한다.

**PG 상태 조회 기준**:
- 1차: `externalPaymentId` 기준 (PG 지원 시)
- fallback: `attemptId` 기준

**상태 전이 절차**:
```
PG 상태 조회 결과
  SUCCESS   → BookingStatus = COMPLETED + PaymentAttempt = SUCCESS
  FAILED    → BookingStatus = FAILED + PaymentAttempt = FAILED + StockPort.incr()
  NOT_FOUND → UNKNOWN 유지 + exponential backoff 재시도
  TIMEOUT   → NOT_FOUND와 동일 경로

재시도: N=3, exponential backoff + jitter
  1회: 30s + jitter, 2회: 60s + jitter, 3회: 120s + jitter
N 초과 → [RECONCILE_FAILED] 로그 + 운영자 에스컬레이션
```

**핵심 원칙**:

- **NOT_FOUND ≠ FAILED**: PG eventual consistency로 조회 시점에 없을 수 있음. NOT_FOUND를 즉시 FAILED로 처리하면 "청구됨 + 주문 취소" 최악 케이스 발생.

- **CAS 상태 전이 강제**:
  ```sql
  UPDATE booking SET status = 'COMPLETED'
  WHERE id = ? AND status = 'PG_PENDING'
    AND updated_at < NOW() - INTERVAL '6 minutes'
  -- rows_affected == 0이면 이미 다른 워커가 처리 → skip
  ```

- **FAILED 전이 조건**: PG 명확한 실패 코드 또는 UNKNOWN_TTL 초과 + 운영자 확인. NOT_FOUND 즉시 FAILED 금지. **retry 횟수 소진만으로 FAILED 전이하지 않는다** — N회 실패 = UNKNOWN 유지 + 운영자 에스컬레이션.

---

### 결정 3 — UNKNOWN 상태

`UNKNOWN`은 Booking 상태로 명시적으로 모델링한다. **PG 상태가 아닌, 우리 시스템이 PG 응답을 받지 못한 상태**를 의미한다.

- `UNKNOWN_TTL` = 설정값 (기본 5~10분, PG/결제수단별 override 가능)
- 재고: **HARD HOLD** 유지 — flash-sale 정합성 우선. UNKNOWN_TTL 단축이 Soft Release보다 안전한 대안.

**사용자 인터랙션 정책**:
```
조회 응답: HTTP 200
{
  "status": "PENDING_CONFIRMATION",
  "message": "결제 확인 중입니다. 잠시 후 다시 확인해주세요.",
  "retryAfterSeconds": 30
}

결제 재시도: 경로 통제
  - 동일 PaymentAttempt retry → 허용
  - 새 수단으로 새 PaymentAttempt → 허용
  - 암묵적 중복 결제 → 409 Conflict

취소 요청: CancellationIntent로 defer (결정 4 참조)
```

---

### 결정 4 — CancellationIntent

UNKNOWN 상태에서 즉시 취소하면 "청구됨 + 취소됨" 불일치가 발생한다. 사용자의 취소 의향을 저장하고 상태 확정 후 실행한다.

```sql
-- CancellationIntent 테이블 제약
UNIQUE (booking_id, refund_target)  -- 복합 결제 환불 idempotency (예: CARD, POINT)
```

**상태 머신**:
```
REQUESTED → CANCELLED  (번복, PROCESSING 전에만 가능)
REQUESTED → PROCESSING → RETRYING → PROCESSED
                                   → FAILED
```

**Reconciliation 완료 후 처리**:
- COMPLETED 확정 → 환불 실행:
  ```sql
  UPDATE booking SET status = 'REFUND_PENDING'
  WHERE id = ? AND status = 'COMPLETED'
  -- CAS 필수 — Reconciliation과 동시 처리 경쟁 방어
  ```
- FAILED 확정 → Intent 소멸 (결제 없음), 사용자 알림

---

### 결정 5 — Late Success 처리

시스템이 FAILED 처리(재고 복구 완료) 후 PG 조회에서 SUCCESS가 확인되는 케이스를 방어한다.

```
Late Success 감지 조건: BookingStatus == FAILED && PG 조회 → SUCCESS

기본 전략 (refund-first):
  자동 환불 트리거 (CancellationIntent 자동 생성)
  + 사용자에게 "결제 성공 확인 → 즉시 환불 처리 중" 알림 (필수)

[LATE_SUCCESS] 이벤트 기록 필수 (CS 추적)
[LATE_SUCCESS_UNRECOVERABLE] → 즉각 운영자 에스컬레이션
```

Late Success는 무시할 수 없다. 사용자 알림 없는 상태 전환 금지.

---

### Booking 전체 상태 모델 (본 ADR 책임 범위)

```
HOLD → PG_PENDING → COMPLETED → REFUND_PENDING
                  → FAILED
                  → UNKNOWN

REFUND_PENDING → (ADR-012 범위)
              → REFUND_COMPLETED
              → REFUND_FAILED
```

### 고려 사항 (Non-goal / 구현 단계 결정)

- **단일 타임라인 뷰**: Booking + PaymentAttempt + CancellationIntent 상태 분산 시 `event_log` 또는 Read Model 필요. ERD 설계 단계에서 결정.
- **Observability**: `attemptId` 기준 tracing, `[RECONCILE_*]` 구조화 로그. 구현 시 계측.
- **Manual Override Admin API**: 운영자 강제 상태 전이. 별도 운영 요구사항.
- **PG Capability Matrix**: `externalPaymentId` 기준 조회 지원 여부는 각 PG Adapter 구현 시 확인.

### 관련 ADR cross-reference

- ADR-006: idempotency_key 원천 (UUID, Redis+DB 저장)
- ADR-008: PG_PENDING 상태 + 6분 유예 만료 — 본 ADR의 트리거 조건
- ADR-009: "미결정 영역 — PG 응답 timeout 처리" — 본 ADR이 완결
- ADR-010: Outbox 폴러 @Scheduled + ShedLock 패턴 재사용

## Consequences

**긍정 결과**
- PG idempotency가 도메인 Entity(PaymentAttempt)로 명시적으로 관리됨 — 이중결제 위험 제거.
- ADR-008/009에서 미결정으로 남겨진 "PG 응답 timeout" 시나리오 완결.
- NOT_FOUND ≠ FAILED 원칙으로 "청구됨 + 주문 없음" 최악 케이스 방어.
- Reconciliation 워커가 ADR-010 패턴을 재사용 — 새 인프라 의존성 없음.
- CancellationIntent로 UNKNOWN 상태에서도 사용자 취소 의향 처리 가능.
- Late Success 처리로 FAILED 전이 이후의 PG SUCCESS 감지 대응.

**부정 결과**
- Reconciliation 창(최대 ~3.5분) 동안 재고가 PG_PENDING에 묶임 — ADR-008 trade-off 연장.
- UNKNOWN_TTL(5~10분) 동안 재고 HARD HOLD — 핫 아이템에서 판매 기회 손실 가능 (정합성 우선 선택).
- Reconciliation 실패(N회 초과) 시 운영자 수동 개입 필요.
- Late Success 처리 후 사용자 알림 미발송 시 CS 혼란 발생 (알림 필수화로 완화).
- PaymentAttempt, CancellationIntent 추가로 상태 추적 복잡도 증가 (타임라인 뷰 필요).

**재검토 시점**
- PG 장애로 RECONCILE_FAILED 빈도가 허용치 초과 시 (retry N, TTL 조정 검토)
- UNKNOWN_TTL 동안 판매 손실이 비즈니스 임계치 초과 시 (Soft Release 전략 재검토)
- Late Success가 빈번히 발생하는 경우 (PG 응답 지연 SLO 재평가)
- Reconciliation 워커 부하가 Outbox 폴러와 충돌 시 (별도 스레드 풀 분리 검토)

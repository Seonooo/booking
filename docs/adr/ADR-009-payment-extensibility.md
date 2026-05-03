# ADR-2026-05-01-009: 결제 수단 확장성 — 두 계층 Strategy + 도메인 검증 + Saga 보상 트랜잭션

## Status

**Accepted (정정됨 2026-05-01)**

### 정정 사유 (2026-05-01)

본 ADR의 초기 작성본은 "한국 PG 환경에서 Two-Phase Commit(Authorize-Capture)이 보편적으로 지원되지 않는다"는 검증되지 않은 전제로 Two-Phase Commit을 기각했다. 후속 리서치 결과 이 전제는 부정확했다.

- **토스페이먼츠는 결제 요청 / 인증 / 승인 단계를 명시적으로 분리**해 제공한다. 인증된 결제는 승인 API 호출 전에는 실제 청구되지 않으며, 성공 페이지 리다이렉트 후 10분 이내 승인 API를 호출하지 않으면 자동 만료된다. 이는 사실상 Authorize-Capture 모델의 PG 측 구현이다.
- **수동 매입 API**가 별도 지원된다. 결제 시점과 상품 제공 시점에 시차가 있는 서비스(호텔/렌터카 등)를 위해 승인-매입을 명시적으로 분리할 수 있다.
- **부분 취소 API**도 표준 지원된다. `cancelAmount` 파라미터로 결제 금액 일부만 취소 가능하며, 복합 결제의 일부 취소도 가능하다.

따라서 Two-Phase Commit 기각의 정당화는 *"한국 PG가 지원 안 함"*이 아니라 *"운영 복잡도와 호환성 측면에서 Saga가 우월"*로 정정한다. 결정 자체(Saga 채택)는 변경 없다.

이 정정은 ADR 작성 시 검증되지 않은 환경 제약을 사실로 단정한 실수에 대한 자기 교정이다. ADR이 후임자에게 잘못된 환경 정보를 학습시킬 위험을 차단한다.

---

## Context

본 시스템 요구사항은 다음을 명시한다.
> "결제 수단: 신용카드, Y페이, Y포인트를 지원합니다. 복합 결제: (신용카드 + 포인트) 또는 (Y페이 + 포인트) 가능. 단, 신용카드와 Y페이는 혼용 불가합니다. 향후 새로운 결제 수단이 추가되어도 Booking API의 비즈니스 로직 수정을 최소화할 수 있는 구조를 적용하고 DECISIONS.md에 상세히 기술해 주시기 바랍니다."

이 요구사항은 네 개의 결정 축을 갖는다.

1. **Strategy 분리 단위**: 결제 수단별로 어떤 추상화로 묶을 것인가
2. **복합 결제 처리 순서와 트랜잭션 모델**: 외부 PG와 내부 DB가 섞인 환경에서 정합성을 어떻게 보장할 것인가
3. **검증 로직 위치**: 비즈니스 정책(혼용 불가 등)을 어디에 둘 것인가
4. **OCP 준수 정도**: 새 결제 수단 추가 시 수정 범위를 어디까지 좁힐 수 있는가

가장 어려운 결정은 축 2다. 분산 트랜잭션 모델은 크게 세 가지가 가능하다.

**Two-Phase Commit (Authorize-Capture)**: 한국 PG 환경에서도 가능하다. 토스페이먼츠는 결제 요청 / 인증 / 승인을 분리하며, 인증 후 10분 hold 후 자동 만료되는 구조를 PG 측에서 자체 제공한다. 수동 매입 API도 별도 지원되어 승인-매입을 명시적으로 분리할 수 있다. 다만 이 모델은 매입 단계가 추가되어 API 호출이 한 번 더 필요하고, 매입 만료 시간 관리가 운영 부담이며, Y페이/카카오페이 등 간편결제의 경우 가맹점별 지원 여부가 상이하다.

**Saga (즉시 매입 + 보상)**: PG 호출 시 즉시 청구가 일어나며, 후속 단계 실패 시 PG의 부분 취소 API로 보상한다. 한국 PG들은 부분 취소를 표준 지원하며, 복합 결제의 일부만 취소도 가능하다. 토스뱅크 환전 서비스 등 한국 대형 시스템에서도 성능과 가용성을 이유로 Saga를 채택한다. 단점은 DB 커밋 실패 시 PG에 보상 호출이 필요하며, 그 호출 자체가 실패할 수 있어 Outbox 재시도 보장이 추가로 필요하다.

**전통 ACID 분산 트랜잭션 (XA 등)**: 외부 시스템과의 분산 트랜잭션은 사실상 사장됐다. PG 호출이 3~10초 걸리는 동안 DB lock을 점유하면 1000 TPS에서 lock contention이 발생하며, 가용성도 크게 저하된다.

또한 카드/Y페이(외부 API 호출)와 포인트(내부 DB 차감) 사이에는 본질적 추상화 갭이 존재한다. 외부 결제는 응답 지연, timeout, 보상 트랜잭션이 필요하지만, 내부 결제는 DB 트랜잭션 롤백으로 단순 처리 가능하다. 이를 단일 인터페이스로만 묶으면 추상화가 거짓말을 하게 된다.

## Options Considered

### 축 1. Strategy 분리 단위

| 옵션 | Pros | Cons |
|---|---|---|
| A. 단일 인터페이스 (`PaymentStrategy`) — 카드/Y페이/포인트 평면 분리 | 단순 | 외부/내부 결제의 본질적 차이를 추상화가 가리지 못함, 보상 트랜잭션 책임이 모호 |
| **B. 두 계층 분리 (External / Internal)** | 외부 PG와 내부 DB의 처리 방식 차이가 타입 시스템에 드러남, 보상 책임 명확 | 클래스 계층 약간 복잡 |
| C. 결제 흐름 단위 (`SinglePayment` / `CompositePayment`) | 단일 vs 복합 분기 명확 | 결제 수단 추가 시 모든 흐름 클래스 수정 — OCP 위반 |

### 축 2. 복합 결제 처리 모델

| 옵션 | Pros | Cons |
|---|---|---|
| A. 포인트 먼저 차감 → 카드 결제 → 실패 시 포인트 롤백 | 내부 자원 먼저 잡으니 안전해 보임 | PG timeout 시 "결제 성공인데 응답 못 받음" 시나리오에서 이중결제 발생 가능 |
| **B. PG 먼저 호출 → DB 트랜잭션(포인트 차감 + Booking) — Outbox로 보상 보장 (Saga)** | 가장 불확실한 PG를 먼저 호출 → 실패 시 DB 변경 없음, DB 트랜잭션은 마지막에 → 일반적 처리, 한국 PG의 부분 취소 표준 지원으로 보상 가능, 모든 PG/페이와 호환, API 호출 단순 | DB 실패 시 PG 보상 필요, 보상 자체 실패 시 Outbox 재시도 필요 |
| C. Two-Phase Commit (Authorize-Capture) | 보상 자체가 거의 발생하지 않음(매입 전 취소는 PG가 자체 처리), 정합성 강함 | 매입 단계가 추가되어 API 호출 1회 더, 매입 만료 시간(토스 10분) 관리 운영 부담, 간편결제(Y페이/카카오페이)는 가맹점별 지원 상이로 호환성 약함, 모든 결제 수단을 동일 모델로 묶기 어려움 |

### 축 3. 검증 위치

| 옵션 | Pros | Cons |
|---|---|---|
| A. Controller (입력 검증) | DTO 단계에서 차단 | 비즈니스 규칙이 표현 계층으로 누출, 다른 진입점에서 검증 누락 |
| B. Service (비즈니스 검증) | 일반적 패턴 | 다른 Service/Batch가 같은 객체 만들 때 검증 누락 가능 |
| **C. Domain Value Object 불변식** | 객체 생성 시점에 항상 유효 보장, 검증 누락 불가능 | DDD 학습 부담 |

### 축 4. OCP 측정 — 카카오페이 추가 시 수정 파일

| 설계 | 수정 기존 파일 | 신규 파일 |
|---|---|---|
| if-else 분기 | `BookingService` 등 1~3개 | 0개 |
| 단일 인터페이스 Strategy | 0개 (등록만) | `KakaoPayPayment` |
| **두 계층 Strategy + Domain 검증** | `PaymentComposition` (혼용 정책 추가 시에만) | `KakaoPayPayment` |

## Decision

**축 1: 옵션 B — 두 계층 Strategy 분리 (External / Internal)**
**축 2: 옵션 B — Saga 모델 (PG 먼저 호출 후 DB 트랜잭션, Outbox로 보상 보장)**
**축 3: 옵션 C — Domain Value Object의 불변식으로 검증**
**축 4: 카카오페이 추가 시 신규 파일 1개 + 기존 파일 0~1개**

### 클래스 계층

```
PaymentMethod (interface)
  ├── ExternalPaymentMethod (interface)
  │     ├── 책임: 외부 PG/페이 API 호출, 보상 트랜잭션 (취소 API)
  │     ├── CardPayment
  │     └── YpayPayment
  └── InternalPaymentMethod (interface)
        ├── 책임: 내부 DB 차감, 트랜잭션 롤백
        └── PointPayment
```

### 처리 흐름 (복합 결제: 카드 + 포인트)

```
1. PaymentComposition 생성 (Domain Value Object)
   → 생성자에서 혼용 검증 (외부 결제 수단 1개 초과 불가)
   → 검증 통과해야 객체 존재 가능 (불변식)

2. PG 호출 (가장 불확실한 외부 호출 먼저)
   → CardPayment.execute() → PG 결제 승인 + 매입 (한국 PG 자동 매입 기본)
   → 실패: BookingException, DB 변경 없음, 보상 불필요

3. DB 트랜잭션 (성공 가능성 높은 내부 작업 마지막에)
   BEGIN;
     - PointPayment.execute() → point.balance -= amount
     - INSERT INTO booking (...)
     - INSERT INTO outbox (compensation_payload: paymentKey, amount, ...)
   COMMIT;

4. DB 트랜잭션 실패 시
   → Outbox 테이블의 compensation_payload 활용
   → 토스페이먼츠 결제 취소 API 호출 (cancelAmount로 부분 취소도 가능)
   → 호출 실패 시 재시도 보장 (디테일은 ADR-007에서 다룸)
```

### Saga가 Two-Phase Commit보다 우월한 이유

축 2의 핵심 결정은 *"한국 PG가 Two-Phase를 지원하지 않아서"*가 아니라 *"운영 복잡도와 호환성 측면에서 Saga가 우월하기 때문"*이다.

- **호환성**: 카드는 Two-Phase 가능하지만 Y페이/카카오페이 등 간편결제는 가맹점별 지원이 상이하다. Saga는 모든 결제 수단에 동일하게 적용 가능하다.
- **운영 단순성**: Two-Phase는 매입 단계 추가로 API 호출이 한 번 더 필요하고, 매입 만료 시간(토스 10분) 관리가 운영 부담이다. Saga는 호출 1회로 끝나며, 보상 시에만 추가 호출이 발생한다.
- **보상 가능성 확보**: 한국 PG들은 부분 취소(`cancelAmount`)를 표준 지원하며, 복합 결제의 일부 취소도 가능하다. 즉 Saga의 보상 단계는 PG 측에서 충분히 지원된다.
- **산업 사례**: 토스뱅크 환전 서비스 등 한국 대형 시스템도 성능과 가용성을 이유로 Saga를 채택한다.

### Domain 검증

```java
public class PaymentComposition {
    private final List<PaymentMethod> methods;
    
    public PaymentComposition(List<PaymentMethod> methods) {
        validate(methods);
        this.methods = List.copyOf(methods);
    }
    
    private void validate(List<PaymentMethod> methods) {
        long externalCount = methods.stream()
            .filter(m -> m instanceof ExternalPaymentMethod)
            .count();
        if (externalCount > 1) {
            throw new InvalidPaymentCompositionException(
                "외부 결제 수단(카드/Y페이)은 동시 사용 불가");
        }
        // 추가 정책: 총액 검증, 결제 수단별 한도 등
    }
}
```

검증을 *"카드 + Y페이"* 같은 이름 비교가 아니라 *"외부 결제 수단 1개 초과 불가"* 라는 추상화 수준에서 작성했다. 이렇게 하면 카카오페이 추가 시에도 자동으로 같은 규칙이 적용된다(External 계층 추가만 하면 됨).

### 핵심 trade-off

- **Buy:**
  - 외부/내부 결제의 본질 차이가 타입 시스템에 드러나 컴파일 타임에 실수 차단
  - PG를 먼저 호출해 DB 변경 없는 실패 시나리오를 단순화
  - Domain 불변식으로 모든 진입점에서 검증 보장
  - 카카오페이 추가 시 신규 파일 1개 + 정책 추가 없으면 기존 파일 0개 수정 (OCP 우수)
  - 모든 결제 수단을 동일 Saga 모델로 처리해 일관성 확보
- **Pay:**
  - 클래스 계층이 평면 구조보다 약간 복잡
  - DB 커밋 실패 시 PG 보상이 필요하며, 보상 자체의 신뢰성을 Outbox에 위임 (ADR-007 의존성)
  - PG 응답 timeout 시 *"결제 성공인데 응답 못 받음"* 시나리오는 별도 reconciliation 필요 (운영 절차)
  - Two-Phase 대비 매입 직후 단계의 정합성 보호가 약함 (보상 호출 실패 가능성 존재)

옵션 1A(단일 인터페이스)를 선택하지 않은 이유: 외부/내부 결제의 처리/실패/보상 차이가 본질적이며, 단일 추상화는 거짓말을 한다. 보상 트랜잭션을 모든 PaymentMethod에 강제하면 PointPayment에 의미 없는 메서드가 생긴다.

옵션 2A(포인트 먼저)를 선택하지 않은 이유: PG timeout 시나리오에서 이중결제 위험이 크다. PG가 실제로는 결제 성공했는데 응답을 받지 못한 경우, 우리 시스템은 카드 실패로 판단해 포인트 롤백 → 사용자는 카드 청구 + 포인트 환불을 동시에 받지만 우리 DB에는 booking이 없어 정합성이 깨진다.

옵션 2C(Two-Phase Commit)를 선택하지 않은 이유: 한국 PG가 Two-Phase를 *지원하지 않기 때문이 아니라*, 운영 복잡도와 호환성 측면에서 Saga가 우월하기 때문이다. 토스페이먼츠 등 주요 PG는 인증-승인 분리와 수동 매입 API를 지원하지만, 매입 단계 추가로 API 호출이 한 번 더 필요하고, 간편결제(Y페이/카카오페이)의 경우 가맹점별 지원이 상이해 모든 결제 수단을 동일 모델로 묶기 어렵다. Saga는 모든 PG/페이에 동일 적용 가능하며, 부분 취소 API의 표준 지원으로 보상 가능성도 확보된다.

옵션 3A/3B를 선택하지 않은 이유: Service/Controller 검증은 다른 진입점(Batch, Admin, 향후 Channel)에서 같은 객체를 만들 때 검증을 누락할 수 있다. Domain 불변식은 객체 자체가 유효한 상태로만 존재함을 보장한다.

### 미결정 영역 — PG 응답 timeout 처리

본 ADR은 Saga 보상 트랜잭션을 채택하면서, *"PG 호출 후 응답을 받은 경우"*의 처리만 명시적으로 결정한다. **PG 호출 자체가 응답을 받지 못한 timeout 시나리오는 본 ADR의 범위 밖이다.**

미결정 시나리오:
```
PG 호출 → timeout (응답 없음)
실제 PG 측 상태:
  - 결제 성공시킴 (응답만 못 보냄): 보상(취소) 호출 필요
  - 결제 실패시킴 (응답만 못 보냄): 보상 불필요, 그냥 종료
  - 우리 요청 자체를 못 받음: 보상 불필요
  - 영원히 응답 없음: 무한 미결
```

이 시나리오의 정확한 처리는 PG 결제 상태 조회 API를 통한 분기가 필요하다.

```
[미결정 처리 절차 — 향후 결정 필요]

1. PG timeout 감지 시 일정 간격(예: 30초)으로 상태 조회 API 호출
2. 응답 결과:
   - 결제 성공 확인: booking 확정 + 재고 회수 취소 (보상 of 보상)
   - 결제 실패 확인: 재고 회수 + booking 없음 (정상 종료)
   - 결제 미발견: 우리 요청을 PG가 못 받음, 재고 회수 + booking 없음
   - 조회 자체 timeout: 운영자 알림 + 수동 처리
3. 무한 미결 방지: 상태 조회 N회 반복 후 운영자 에스컬레이션
```

본 ADR-009가 결정한 것:
- PG 응답을 받은 경우의 Saga 보상 전략 (DB 실패 시 PG 취소 호출 + Outbox로 재시도 보장)
- 외부/내부 결제 수단의 두 계층 추상화
- PaymentComposition 도메인 불변식 검증

본 ADR-009가 결정하지 않은 것:
- PG 응답 timeout 시 상태 조회 API 호출 주기와 횟수
- 상태 조회 결과별 분기 로직의 정확한 사양
- 무한 미결 시 운영자 에스컬레이션 절차

이 미결정 영역은 ADR-008(*"강제 회수 + PG 취소 요청"*은 응답 받은 후 시나리오에 한정), ADR-010(Outbox로 보상 재시도 보장)과의 정합성 안에서 향후 별도 ADR 또는 운영 Runbook으로 결정한다. **본 결정 누락 상태에서 ADR-008의 *"강제 회수 + PG 취소 요청"*을 PG timeout 시나리오에 임의 적용하는 것은 금지된다 — PG 실제 상태와의 불일치 발생 가능.**

## Consequences

**긍정 결과**
- 카카오페이 등 새 결제 수단 추가 시 `KakaoPayPayment extends ExternalPaymentMethod` 신규 파일 1개로 끝난다. `BookingService`, `PaymentComposition`은 수정 불필요(혼용 불가 정책이 새로 생기지 않는 한). OCP를 정량적으로 달성.
- PG 먼저 호출하는 흐름으로 가장 흔한 실패 케이스(PG 거절)에서 DB 변경 없이 종료된다. 보상 트랜잭션이 필요한 경우는 DB 커밋 실패라는 드문 경우뿐이며, Outbox로 재시도 보장.
- Domain 불변식 검증으로 BookingService 외 다른 진입점(Admin, Batch)에서도 자동 검증된다.
- "외부 결제 수단 1개 초과 불가"라는 추상화 수준 검증으로, 결제 수단 추가 시 검증 규칙도 자동 적용된다.
- 모든 결제 수단을 단일 Saga 모델로 통일해 처리 일관성 확보.

**부정 결과**
- PG 호출 후 DB 커밋 실패 시 PG 결제는 성공한 상태로 남는다. 이 경우 PG 취소 API 호출이 필요하며, 이 호출 자체가 실패하면 사용자는 청구되었으나 booking 없음 상태가 된다. Outbox 패턴(ADR-007)으로 재시도 보장하지만, 운영상 reconciliation 절차 필요.
- PG 응답 timeout(*"결제 성공했는지 알 수 없음"*) 시나리오는 단순 보상으로 해결되지 않는다. PG에 결제 상태 조회 API를 호출해 실제 상태 확인 후 보상/확정 분기가 필요하다. 이는 운영 절차이며 본 ADR의 범위 밖.
- Two-Phase Commit 대비 매입 직후의 보호 메커니즘이 약하다. Two-Phase에서는 매입 전 취소가 PG 자체 처리이지만, Saga에서는 우리가 명시적으로 취소 API를 호출해야 한다.
- 클래스 계층이 평면 구조보다 깊어져 신규 개발자 학습 비용 약간 증가. 다만 두 계층은 직관적이며 PaymentMethod의 javadoc으로 충분히 설명 가능.
- Domain Value Object 검증은 객체 생성 시점에 발생하므로, 검증 실패가 BookingException 등 도메인 예외로 던져진다. Controller에서 이를 적절히 catch해 사용자 친화적 에러 메시지로 변환하는 책임이 추가된다.

**재검토 시점**
- 모든 사용 PG/페이가 Authorize-Capture(승인-매입 분리)를 동일 사양으로 지원하게 될 때 (Two-Phase Commit으로 전환 시 호환성 부담이 사라짐 — 재평가)
- PG timeout 또는 보상 호출 실패로 인한 reconciliation 사례가 빈번히 발생할 때 (Two-Phase로 보호 메커니즘 강화 검토 또는 PG 상태 조회 자동화)
- 결제 수단 종류가 5개를 초과해 두 계층 분리만으로 추상화가 부족할 때 (계층 추가 또는 Plugin 아키텍처 도입)
- 복합 결제에 외부 수단 2개 이상이 허용되는 정책 변경 시 (PaymentComposition 검증 규칙 변경 + 처리 순서 재설계)
- 토스뱅크 등 산업 사례에서 Saga 대신 다른 패턴이 새 표준으로 자리잡을 때 (재평가)

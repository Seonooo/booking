# DECISIONS.md — 주요 설계 쟁점과 선택 근거

> **Legacy narrative archive.** 본 문서는 초기 설계 단계 narrative이며, 현재 정합 출처는 [`docs/adr/DECISIONS.md`](./docs/adr/DECISIONS.md) (ADR 인덱스) + 개별 ADR 본문이다.
> 본문 내 *"1000 TPS"* 분석은 **설계 capacity 상한 기준** 안전성 평가로 읽으며, ADR 본문과 동일하게 보존된다 (관측 환경은 1~5분간 500~1000 TPS 변동 — `docs/adr/DECISIONS.md` §시스템 환경 요약 참조).
> 신규 결정은 본 파일을 갱신하지 않고 ADR 작성 + `docs/adr/DECISIONS.md` 인덱스 갱신으로 진행한다.

---

## 1. 진입 모델: 큐 vs 재고 카운터

**쟁점**: 1000명이 동시에 몰릴 때 어떻게 10자리만 입장시킬 것인가.

처음에는 Redis ZSET으로 30명 대기열을 만드는 방식(ADR-001)을 설계했다. 그러나 "왜 30명인가"라는 질문에 대한 답이 결제 실패율 추정에 의존한다는 점, 그리고 대기열 자정 직후 30명 이후의 사용자에게는 구조적으로 기회를 막는다는 점이 문제였다.

**선택**: 재고 카운터 기반 단일 진입 모델 (ADR-008)  
→ 재고 = 진입 허용 수. atomic DECR으로 정확히 10명만 입장. 결제 실패 시 INCR으로 반납, 나머지 사용자는 새로고침 폴링으로 재참여.

인터파크·콘서트 예매의 "취켓팅" 모델이 이미 이 구조이며, 사용자도 이 흐름에 익숙하다. N 정당화 문제도 사라진다.

---

## 2. Redis 원자성: WATCH/MULTI vs Lua Script

**쟁점**: 재고 DECR, Rate Limit, 멱등성 키 체크 모두 "검사 후 변경" 패턴이다. 1000 TPS에서 이 연산들이 분리되면 race condition이 발생한다. 어떻게 atomic을 보장할 것인가.

Redis WATCH/MULTI/EXEC(Optimistic Lock) 방식은 충돌 시 클라이언트가 재시도해야 한다. 1000 TPS burst 환경에서는 거의 모든 요청이 충돌 → 재시도 폭주로 이어진다.

**선택**: Lua Script (ADR-002)  
→ 단일 round-trip, 재시도 불필요, race condition 0 보장. "멀티 키니까 Lua"가 아니라 "검사+변경이 분리되면 race condition이 생기니까 Lua"라는 기준으로 적용.

---

## 3. 결제 트랜잭션 모델: Saga vs Two-Phase Commit

**쟁점**: 외부 PG 호출과 DB 트랜잭션을 어떻게 묶을 것인가.

초기 작성 시 "한국 PG는 Two-Phase Commit을 지원하지 않는다"고 단정해 Saga를 선택했다. 이후 리서치로 이 전제가 틀렸음을 확인했다. 토스페이먼츠는 결제 요청/인증/승인 분리, 수동 매입 API를 지원한다.

**재검토 결과**: 그래도 Saga가 우월 (ADR-009 정정됨)  
→ Y페이, 카카오페이 등 간편결제는 가맹점별 Two-Phase 지원이 상이해 모든 결제 수단을 동일 모델로 묶기 어렵다. Saga는 부분 취소 API를 통해 보상이 가능하고, 모든 PG/페이에 동일하게 적용된다.

잘못된 전제로 옳은 결론에 도달한 케이스 — ADR에 정정 사유를 명시해 후임자에게 틀린 정보를 학습시키지 않도록 했다.

---

## 4. 결제 확장성 설계 (ADR-009)

### 4-1. 현재 지원 결제 수단과 복합 결제 규칙

| 결제 수단 | 분류 | 단독 결제 | 비고 |
|----------|------|----------|------|
| 신용카드 | External (외부 PG API) | ✅ | 포인트와 복합 가능 |
| Y페이 | External (외부 PG API) | ✅ | 포인트와 복합 가능 |
| Y포인트 | Internal (내부 DB 차감) | ✅ | External과 복합도 가능 |

복합 결제 허용 조합:
- ✅ 신용카드 단독
- ✅ Y페이 단독
- ✅ Y포인트 단독
- ✅ 신용카드 + Y포인트
- ✅ Y페이 + Y포인트
- ❌ 신용카드 + Y페이 (외부 결제 수단 2개 혼용 불가 — 유일한 제약)

### 4-2. 쟁점: 단일 인터페이스 vs 두 계층

카드/Y페이(외부 PG 호출)와 포인트(내부 DB 차감)는 본질적으로 다르다.

| 특성 | External (카드, Y페이) | Internal (포인트) |
|------|----------------------|-----------------|
| 처리 방식 | 외부 PG API 동기 호출 (1~3초) | DB `UPDATE` (ms) |
| 실패 보상 | PG 취소 API 호출 필요 | DB 트랜잭션 롤백으로 충분 |
| Timeout | 10초, PG_PENDING 상태 관리 필요 | 없음 |

단일 `PaymentStrategy`로 묶으면 `PointPayment`에 `cancel(paymentKey)` 메서드를 강제하게 된다. 포인트는 취소 API가 없으므로 빈 구현이 생긴다. **추상화가 거짓말을 한다.**

**선택**: External / Internal 두 계층 분리

```java
PaymentMethod (interface)
  ├── ExternalPaymentMethod — execute() + cancel()  // 보상 책임 포함
  │     ├── CardPayment
  │     └── YPayPayment
  └── InternalPaymentMethod — execute()             // 보상은 DB 롤백
        └── PointPayment
```

### 4-3. 복합 결제 검증 위치: Controller vs Service vs Domain

| 위치 | 문제 |
|------|------|
| Controller | 비즈니스 규칙이 표현 계층으로 누출. 다른 진입점(Admin, Batch)에서 검증 누락 가능 |
| Service | 다른 Service에서 같은 객체를 만들 때 검증 누락 가능 |
| **Domain Value Object** | 객체 생성 시점에 항상 유효 보장. 어떤 진입점에서도 누락 불가 |

**선택**: `PaymentComposition` Value Object의 생성자 불변식

```java
public PaymentComposition(List<PaymentMethod> methods) {
    long externalCount = methods.stream()
        .filter(m -> m instanceof ExternalPaymentMethod).count();
    if (externalCount > 1)
        throw new InvalidPaymentCompositionException("외부 결제 수단 혼용 불가");
}
```

검증을 **이름 비교**(`"CARD".equals(type)`)가 아닌 **타입 계층 비교**(`instanceof ExternalPaymentMethod`)로 작성한 이유: 카카오페이를 추가하면 자동으로 같은 검증이 적용된다.

### 4-4. OCP 달성 — 새 결제 수단 추가 시 수정 범위

카카오페이 추가 시:

| 작업 | 파일 수 |
|------|--------|
| 신규 생성 | `KakaoPayPayment.java` 1개 |
| 기존 수정 | `BookingService` **0개** / `PaymentComposition` **0개** (새 혼용 정책이 없는 한) |

기존 `BookingService`가 `ExternalPaymentMethod` 타입으로만 다루기 때문에 카카오페이를 추가해도 서비스 로직을 건드릴 필요가 없다.

---

## 5. 장애 대응 및 예외 처리 (ADR-007, ADR-008, ADR-009)

### 5-1. Redis 장애 Fallback 전략

#### 왜 단순 Timeout만으로는 부족한가

Redis가 완전히 죽지 않고 응답이 5ms → 500ms로 느려지는 "slow death" 상황에서는 Sentinel이 감지하지 못한다. 톰캣 스레드들이 Redis 응답 대기로 묶이면서 cascading failure로 전체 시스템이 다운된다.

**선택**: 두 계층 다층 방어 (ADR-007)

| 계층 | 도구 | 커버하는 장애 |
|------|------|-------------|
| 인프라 계층 | Redis Sentinel | Master 노드 다운, 네트워크 단절 (5~15초 내 자동 failover) |
| 애플리케이션 계층 | Resilience4j Circuit Breaker + Bulkhead | slow death (1초 이상 응답), cascading failure 차단 |

#### Fail-Open vs Fail-Closed

일반 API의 산업 권장은 Fail-Open(검사 skip 후 통과)이다. 그러나 이 시스템에서 Fail-Open은 구체적으로:

| 컴포넌트 | Fail-Open 시 결과 |
|---------|-----------------|
| Rate Limit | 자정 burst에 봇이 10석 전부 점유 → 정상 사용자 영구 차단 |
| 재고 카운터 | 초과 판매 → 환불 + 신뢰 손상 (복구 불가) |
| 멱등성 키 | 이중 결제 → 즉각 금전 손실 (복구 불가) |

**선택**: Fail-Closed 통일 — 모든 Redis 의존 컴포넌트는 장애 시 503 반환

503은 사용자가 재시도로 복구 가능. 무결성 훼손은 불가능. **"Fairness 100% > Availability 99.9%"** 를 명시적 SLO로 선언하고 이 선택의 근거로 삼는다.

Circuit Breaker 설정값의 선택 근거 (ADR-007):
- `slidingWindowSize: 5초` — 5분 burst 환경에서 COUNT_BASED는 비율 변동이 심해 TIME_BASED 채택
- `slowCallDurationThreshold: 1000ms` — Redis 정상 응답 5ms 대비 200배. GC pause(< 100ms)는 오탐하지 않는 경계
- `waitDurationInOpenState: 5000ms` — Sentinel failover 완료(5~15초)와 맞춰 첫 Half-Open 시도 타이밍 조율

---

### 5-2. 결제 실패 케이스별 대응 로직

#### 케이스 1: PG 거절 (한도 초과, 카드 정지 등)

```
PG.execute() → PG 거절 응답 수신
→ BookingException 발생
→ DB 변경 없음 (트랜잭션 시작 전)
→ 재고 INCR (hold 반납)
→ 사용자 응답: 400 + "결제가 거절되었습니다"
```

보상 트랜잭션 불필요. DB를 건드리기 전에 실패했으므로 정합성 문제 없음.

#### 케이스 2: PG Timeout (응답 미수신)

```
PG.execute() → 10초 timeout 초과
→ 상태: PG_PENDING 유지, 60초 추가 유예 (ADR-008)
→ 6분 도달 (PG 유예 만료):
   → 강제 재고 INCR + PG 취소 API 호출 (Saga 보상, ADR-009)
   → 취소 API 실패 시 → Outbox 재시도 스케줄러가 보장
```

> ⚠️ **ADR 미결정 영역**: "PG 취소 API를 호출했으나 실제로 결제가 성공했는지 알 수 없는" reconciliation 시나리오 (예: timeout 중 PG에서 결제 확정)는 ADR-009에서 **운영 절차로 분류**하며 본 설계 범위 밖이다. 이 케이스가 빈번히 발생할 경우 PG 상태 조회 자동화 ADR 추가를 재검토한다.

#### 케이스 3: DB 커밋 실패 (PG 청구 후)

```
PG.execute() → 결제 성공
DB 트랜잭션 → 커밋 실패
→ PG는 이미 청구된 상태
→ Outbox compensation_payload 활용 → PG 취소 API 호출 (Saga 보상)
→ 취소 API 실패 시 → Outbox 재시도 스케줄러가 보장
→ 취소 성공 시 → 재고 INCR
```

"PG 청구됨 + DB 없음" 상태가 되지 않도록 Outbox 패턴으로 보상 신뢰성을 보장한다.

#### 케이스 4: 재고 소진

```
Redis 재고 카운터 DECR → 재고 0
→ 200 + { "status": "SOLD_OUT", "message": "현재 잔여 재고가 없습니다" }
→ 클라이언트: 페이지 유지 + 폴링 (재고 반납 시 재시도 가능)
```

재고 소진은 오류가 아닌 정상 흐름. 4xx 응답을 쓰지 않는 이유: 클라이언트가 재시도할 여지를 주기 위해 200으로 상태를 전달한다.

#### 케이스 5: 멱등성 중복 요청

```
동일 idempotency_key + 동일 payload, 처리 중 → 409 Conflict
동일 idempotency_key + 동일 payload, 완료 → 200 + 캐시 응답
동일 idempotency_key + 다른 payload    → 422 Unprocessable Entity
```

422는 변조 시도를 의미하므로 보안 모니터링 대상으로 분류한다.

#### 케이스 요약

| 케이스 | HTTP 응답 | 재고 처리 | 보상 필요 |
|--------|----------|----------|---------|
| PG 거절 (한도 초과 등) | 400 | INCR (반납) | 없음 |
| PG Timeout | — (대기) | 60초 유예 후 INCR | PG 취소 API (Outbox 보장) |
| DB 커밋 실패 | 500 | INCR (Saga 후) | PG 취소 API |
| 재고 소진 | 200 SOLD_OUT | 해당 없음 | 없음 |
| Rate Limit 초과 | 429 | 해당 없음 | 없음 |
| Redis 장애 | 503 | 해당 없음 | 없음 |
| 멱등성 중복 | 409 / 422 | 해당 없음 | 없음 |

---

## 6. 이벤트 발행: 직접 발행 vs Transactional Outbox  

**쟁점**: DB 커밋 직후 이벤트를 발행하면 간단하다. 왜 Outbox 테이블이 필요한가.

```
T+0: PG 호출 성공
T+1: DB Booking INSERT 성공
T+1.5: 서버 다운
결과: booking은 있는데 알림 이벤트는 영구 유실
```

결제 시스템에서 알림/정산 누락은 사용자 신뢰 손상이다.

**선택**: Transactional Outbox (ADR-010)  
→ Outbox INSERT를 Booking과 같은 트랜잭션에 포함. 서버 다운 후 재기동 시 PENDING 이벤트를 폴러가 자동 재발송. 이벤트 유실 0% 보장(Outbox 진입한 이벤트 기준).

단, Outbox INSERT 자체가 실패하면 어떻게 할 것인가: PG 청구는 이미 완료됐으므로 트랜잭션 롤백 시 "돈은 빠졌는데 예약 없음"이 된다. **Outbox INSERT 실패 시 트랜잭션을 롤백하지 않고 fallback 로깅 후 운영 복구**로 처리한다. 가용성 우선 결정.

---

## 7. 이벤트 전달 도구: Kafka vs In-Process

**쟁점**: Outbox 이벤트를 어떻게 소비할 것인가. 처음부터 Kafka를 써야 하지 않는가.

현재 시스템은 컨슈머 1~2개, 단일 애플리케이션, 평시 50 TPS다. Kafka는 fan-out, MSA 통신, 긴 메시지 보존이 가치 있을 때 도입하는 도구다. 현재 조건은 어느 것도 해당하지 않는다.

**선택**: In-Process + EventPublisher 인터페이스 추상화 (ADR-010)  
→ 현재는 Spring ApplicationEventPublisher로 단순하게. 향후 컨슈머 3개 이상, MSA 분리, TPS 1000 이상 중 하나가 관찰되면 `KafkaEventPublisher implements EventPublisher` 구현체만 교체. YAGNI 원칙 준수.

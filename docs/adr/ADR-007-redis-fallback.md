# ADR-2026-05-01-007: Redis 장애 대응 — Sentinel HA + Resilience4j 서킷 브레이커 + Fail-Closed 통일

## Status

Accepted

## Context

본 시스템은 다음 다섯 가지 환경적 특수성을 가진다.

1. **5분 burst 트래픽**: 평시 50 TPS, 자정 burst 시 5분간 1000 TPS. 매출의 상당 부분이 이 5분 안에 발생.
2. **재고 10개 한정**: 누적 30만 요청 중 성공 가능 10건. 99.997%는 실패 운명.
3. **공정성이 핵심 SLO**: ADR-004에서 약한 동등 시도 기회를 갖는 선착순으로 정의. 무결성 훼손 시 사용자 신뢰 영구 손상.
4. **Redis가 단일 외부 의존성**: 재고 카운터(ADR-008), Lua atomic(ADR-002), Rate Limit(ADR-005), 멱등성 키(ADR-006), 결제 hold 모두 Redis에 의존.
5. **결제 시스템**: 정합성 깨지면 즉각 금전 손실 + 보상 비용 + 브랜드 이미지 훼손.

이 환경에서 Redis 장애 시 발생 가능한 시나리오는 두 가지 계층에서 발생한다.

**인프라 계층**: Master 노드 다운, 네트워크 단절, OOM 등. 이 계층은 Sentinel 같은 HA 솔루션으로 자동 failover 가능.

**애플리케이션 계층**: Redis가 완전히 죽지는 않았으나 응답 속도가 평소 5ms에서 수백 ms로 늘어지는 *"slow death"*. PING은 응답하므로 Sentinel이 감지 못함. 이때 톰캣 스레드들이 Redis 응답 대기로 묶이며, 결국 cascading failure로 전체 시스템 다운.

두 계층 모두 보호되어야 하며, 하나만으로는 충분하지 않다. Sentinel은 인프라 계층 장애에는 강력하지만 slow death를 잡지 못한다. 서킷 브레이커는 slow death를 잡지만 인프라 failover는 못 한다. **두 계층을 동시에 보호**하는 다층 방어가 필요하다.

또한 Redis 장애 시 응답 정책의 결정이 필요하다. 일반 API의 산업 권장은 Fail-Open(검사 skip 후 통과)이지만, 본 시스템은 결제 + 공정성 SLO 환경이므로 일반 권장이 그대로 적용되지 않는다.

## Options Considered

### 축 1. 인프라 계층 HA

| 옵션 | Pros | Cons |
|---|---|---|
| A. 단일 인스턴스 | 단순 | SPOF, 장애 시 100% 다운 |
| **B. Sentinel** | 자동 failover, 단순한 운영 | 비동기 복제로 일부 데이터 손실 가능, split-brain 위험 (mitigation 필요) |
| C. Cluster | 샤딩 + HA | 1000 TPS 환경에 과한 복잡도, 운영 부담 ↑ |

### 축 2. 애플리케이션 계층 보호

| 옵션 | Pros | Cons |
|---|---|---|
| A. Timeout만 (서킷 브레이커 없음) | 단순 | slow death 못 잡음, cascading failure 미차단 |
| **B. Resilience4j 서킷 브레이커 + Bulkhead** | slow death 차단, 톰캣 스레드 보호, 자동 복구 | 라이브러리 의존성 추가, 설정 튜닝 필요 |
| C. 비동기 아키텍처 전환 (Kafka 등) | Non-blocking 자원 효율 | 운영 복잡도 폭증, latency 증가, 우리 동기 모델과 충돌 |

### 축 3. 서킷 브레이커 Sliding Window 타입

| 옵션 | Pros | Cons |
|---|---|---|
| A. COUNT_BASED | 단순, 일반 e-commerce 환경에 적합 | burst 트래픽에서 비율 변동 심함, 오탐 가능 |
| **B. TIME_BASED** | 5분 burst 환경에서 안정적 비율 산출 | 약간 복잡한 설정 |

### 축 4. Fallback 정책

| 옵션 | Pros | Cons |
|---|---|---|
| A. Fail-Open (검사 skip, 통과) | 가용성 우선, 일반 API 산업 권장 | 무결성 훼손 위험, 봇/매크로 노출, 공정성 SLO 위반 |
| **B. Fail-Closed (503 통일)** | 무결성 100% 보장, 모든 컴포넌트 일관 정책, 운영 단순 | 가용성 일시 저하, 매출 손실 |
| C. 컴포넌트별 차등 (Rate Limit만 Fail-Open) | 매출 보호 | 정책 일관성 부족, 운영 복잡, Rate Limit이 봇 방어 보조 역할에 한정되므로 효과 제한적 |

## Decision

**축 1: 옵션 B — Sentinel 채택**
**축 2: 옵션 B — Resilience4j 서킷 브레이커 + Bulkhead 도입**
**축 3: 옵션 B — TIME_BASED Sliding Window**
**축 4: 옵션 B — Fail-Closed 통일 (모든 fallback이 503 응답)**

### 상세 구성

#### Sentinel (인프라 계층)

```
구성: Master 1 + Replica 2 + Sentinel 3 (별도 호스트)
설정값:
  - down-after-milliseconds: 5000 (5초)
  - failover-timeout: 15000 (15초)
  - parallel-syncs: 1
  - quorum: 2
  - min-replicas-to-write: 1   # split-brain 방어
  - min-replicas-max-lag: 10
```

**5초 down-after 채택 근거**: 5분 burst 환경에서 30초 down-after는 burst 윈도우의 10%(매출의 10%) 손실을 의미한다. 일시적 네트워크 hiccup으로 인한 오탐 위험은 quorum 2 + Sentinel 3 다수결 합의로 완화된다.

**min-replicas-to-write 1 채택 근거**: split-brain 발생 시 두 master가 동시 쓰기를 받아 30초 분량 데이터 유실이 발생한 산업 장애 사례가 보고됐다. 결제 시스템에서 이는 치명적이므로 필수 설정.

#### Resilience4j 서킷 브레이커 (애플리케이션 계층)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redisOps:
        slidingWindowType: TIME_BASED
        slidingWindowSize: 5  # 5초
        minimumNumberOfCalls: 50
        failureRateThreshold: 50
        slowCallDurationThreshold: 1000  # 1초
        slowCallRateThreshold: 50
        waitDurationInOpenState: 5000  # 5초
        permittedNumberOfCallsInHalfOpenState: 10
        automaticTransitionFromOpenToHalfOpenEnabled: true
```

**TIME_BASED 채택 근거**: 5분 burst 환경에서는 트래픽이 순간 폭증하므로 COUNT_BASED는 짧은 시간 내 비율 변동이 너무 커 오탐 위험이 높다. TIME_BASED는 burst 상황에서도 안정적으로 동작한다.

**slowCallDuration 1초 채택 근거**: Redis 정상 응답이 평균 5ms이므로 1초는 200배 지연. 이 수준에서 slow death로 판단하기 충분하며, 일시적 GC pause(보통 < 100ms)는 오탐하지 않는 경계.

**minimumNumberOfCalls 50 채택 근거**: 1000 TPS 환경에서 5초 윈도우 = 5000건. 50은 1% 표본으로 통계적 유의성 충분하면서, 트래픽 적은 시간(평시 50 TPS = 5초간 250건)에도 표본 확보 가능.

**waitDurationInOpenState 5초 채택 근거**: Sentinel failover가 5~15초 안에 완료되므로, 5초 후 Half-Open 시도하면 첫 시도가 실패해도 다음 5초 cycle에서 복구 감지 가능. 3초 미만은 Flapping 위험, 10초 이상은 사용자 체감 지연 과다.

#### Bulkhead

```yaml
resilience4j:
  bulkhead:
    instances:
      redisOps:
        maxConcurrentCalls: 100
        maxWaitDuration: 0ms
```

**100개 채택 근거**: 톰캣 기본 스레드 풀 200개의 50%. Redis 호출이 톰캣 스레드 절반 이상을 점유하지 못하게 하여 단순 조회 등 다른 요청 처리 여력 보장.

#### Fallback 정책 — 모든 컴포넌트 Fail-Closed

```java
@CircuitBreaker(name = "redisOps", fallbackMethod = "redisFallback")
public boolean checkRateLimit(String userId) { ... }

public boolean redisFallback(String userId, Exception e) {
    throw new ServiceUnavailableException("일시적 장애로 요청을 처리할 수 없습니다.");
}
```

**모든 Redis 의존 컴포넌트(재고, 큐 진입, Rate Limit, 멱등성, 결제 hold) 동일 정책**: 서킷 OPEN 또는 Bulkhead full 시 503 응답.

### Fail-Closed 통일 채택의 SLO Decision

본 결정의 핵심은 *"Availability 99.9% < Fairness 100%"*의 명시적 정책 선택이다.

Fail-Open은 일반 API에서 산업 권장이지만, 본 시스템에서 Fail-Open은 다음을 의미한다.
- Rate Limit Fail-Open → 봇/매크로가 그 시간에 큐 30석 점유 가능 → 정상 사용자 영구 차단
- 재고 카운터 Fail-Open → 초과 판매 가능 → 환불/사과/브랜드 손상
- 멱등성 Fail-Open → 이중 결제 가능 → 즉각 금전 손실

이 모든 시나리오는 *"한 번 발생하면 영구적으로 무결성을 훼손"*한다. 503 응답으로 인한 일시적 가용성 저하는 사용자가 재시도로 복구 가능하지만, 무결성 훼손은 보상 비용 + 브랜드 이미지 영구 손상으로 이어진다.

따라서 본 시스템의 SLO는 다음과 같이 정의한다.
> **Fairness 100% > Availability 99.9%**
> 
> 503 응답은 단순 장애가 아니라, 공정성/무결성을 지키기 위해 가용성을 의도적으로 희생한 정책적 결정이다.

이 정책 선언이 본 ADR의 가장 중요한 부분이다. 향후 모든 Redis 관련 fallback 결정은 이 SLO를 기준으로 평가되어야 한다.

### 핵심 trade-off

- **Buy:**
  - 두 계층 다층 방어로 인프라 장애와 slow death 모두 차단
  - 모든 Redis 호출에 일관된 fallback 정책 적용 → 운영 단순
  - 무결성 100% 보장으로 브랜드 이미지 보호
  - 5분 burst 환경에 최적화된 설정값 (TIME_BASED, 5초 down-after)
  - Sentinel + 서킷 브레이커 자동 복구로 운영 개입 최소화
- **Pay:**
  - Redis 장애 시 일시적 가용성 저하 (Sentinel failover 5~15초 + 서킷 브레이커 wait 5초)
  - 5분 burst 동안 장애 발생 시 매출 일부 손실 (윈도우의 5~10%)
  - Resilience4j 라이브러리 의존성 추가 + 설정 튜닝 운영 부담
  - 비동기 복제로 인한 미세한 데이터 손실 가능 (운영 절차로 보완)
  - 일반 API 산업 권장(Fail-Open)과 다른 정책 선택 → 신규 개발자 학습 필요

옵션 1A(단일 인스턴스)를 선택하지 않은 이유: SPOF로 결제 시스템에 부적합. 자정 burst 중 Master 다운 시 매출 100% 손실.

옵션 1C(Cluster)를 선택하지 않은 이유: 1000 TPS는 단일 Master로 충분히 처리(Redis 단일 인스턴스 10만 TPS 가능). Cluster는 샤딩 필요한 데이터 양에서 가치 있는데, 본 시스템은 30석 내외 데이터만 다룸.

옵션 2A(Timeout만)를 선택하지 않은 이유: slow death 시나리오에서 무력. Redis가 평소 5ms → 500ms로 늘어지면 timeout 1초로는 못 잡고, 톰캣 스레드는 점진적으로 묶여 cascading failure 발생.

옵션 2C(비동기 전환)를 선택하지 않은 이유: 본 시스템의 결제 흐름은 ADR-009에서 동기 모델로 결정됨. 비동기 전환은 latency 증가 + 운영 복잡도 폭증 + 결제 정합성 도전.

옵션 3A(COUNT_BASED)를 선택하지 않은 이유: 5분 burst 환경에서 짧은 시간 내 trafic 급증으로 비율 변동 심해 오탐 위험. 일반 e-commerce 환경에서는 COUNT_BASED가 적합하지만 본 시스템은 다름.

옵션 4A(Fail-Open)를 선택하지 않은 이유: 무결성 영구 훼손 위험. 본 시스템 SLO에 위반.

옵션 4C(컴포넌트별 차등)를 선택하지 않은 이유: Rate Limit만 Fail-Open해도 봇 방어 보조 역할에 한정되어 효과 제한적. 다른 컴포넌트 503 응답이 동시 발생하면 사용자 경험은 사실상 503이라 Fail-Open 의미 약화. 정책 일관성 부족으로 운영 복잡도 ↑.

## Consequences

**긍정 결과**
- Redis 단일 노드 장애 시 5~15초 안에 자동 복구 (Sentinel failover).
- Slow death 시나리오에서 톰캣 스레드 보호로 cascading failure 차단 (Bulkhead 100개 제한).
- 모든 Redis 호출에 일관된 503 응답으로 운영자가 한 가지 fallback만 모니터링하면 됨.
- min-replicas-to-write 1로 split-brain 시 데이터 손실 사전 차단.
- 자동 복구 메커니즘 (Sentinel failover + 서킷 브레이커 Half-Open 자동 전환)으로 운영 개입 최소화.
- Fairness 100% SLO 보장으로 브랜드 신뢰 보호.

**부정 결과**
- 5분 burst 중 Redis 장애 시 매출 5~10% 손실 가능 (failover + 서킷 브레이커 wait 시간).
- 비동기 복제로 인한 수 ms~수 초 데이터 손실 가능. 재고 카운터 cross-check 운영 절차 필요.
- 서킷 브레이커 Flapping(상태 반복 변경) 위험. 모니터링 + Slack 알림 필수.
- 일반 API 산업 권장과 다른 Fail-Closed 정책 → 신규 개발자 온보딩 시 명시적 설명 필요.
- Resilience4j 설정값 튜닝이 burst 트래픽 패턴 변화 시 재검토 필요.
- Sentinel + Resilience4j 조합의 운영 복잡도 (단순 단일 Redis 대비).

**모니터링 필수 메트릭**
- Sentinel failover 발생 횟수 및 소요 시간
- 서킷 브레이커 상태 변경 (CLOSED → OPEN, OPEN → HALF_OPEN, HALF_OPEN → CLOSED)
- Bulkhead full 발생 빈도
- Redis 응답 시간 p50/p95/p99
- 503 응답 비율 (burst 윈도우별)
- Flapping 감지 (상태 변경이 분당 N회 이상이면 알림)

**재검토 시점**
- Burst 트래픽 패턴이 변경될 때 (5분에서 10분으로 늘어나면 sliding window 5초가 적절한지 재평가)
- 데이터 양이 증가하여 단일 Master 메모리 한계에 근접할 때 (Cluster 전환 검토)
- 503 응답 비율이 SLO 임계치를 초과할 때 (현재 정책 vs 매출 손실 재평가)
- Redis 응답 시간 평균이 100ms 이상으로 항시 유지될 때 (slowCallDuration 1초 임계값 재평가)
- 서킷 브레이커 Flapping이 빈번하면 (waitDurationInOpenState 또는 minimumNumberOfCalls 조정)
- Bulkhead full이 평시에 빈번하게 발생하면 (maxConcurrentCalls 또는 톰캣 스레드 풀 조정)
- 신규 결제 수단/외부 의존성 추가로 단일 redisOps 서킷 브레이커 분리 필요할 때

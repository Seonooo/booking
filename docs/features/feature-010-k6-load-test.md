# Feature 010: k6 부하 테스트 — 1000 TPS burst SLO 검증 (REQUIREMENTS Req 1·2)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Planning | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 외부 대화 참조 금지.

## Request

> 사용자 *"a, c, b 순서"* — feature-008 머지 후 단계 c (k6 부하 테스트). REQUIREMENTS Req 1 *정합성 / 공정성* + Req 2 *고가용성* 본격 검증. ADR-007 다층 방어 / ADR-008 재고 정확성 / DECISIONS.md §10 4층 방어 시퀀스 동작 확인.

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `REQUIREMENTS.md` Req 1 | 미달/초과판매 0건 + 공정성 (모든 사용자 동등 시도) |
| `REQUIREMENTS.md` Req 2 | TPS 평시 50 / burst 500~1000 (1~5분) / 설계 capacity 1000 TPS 상한 |
| `DECISIONS.md` §10 §TPS burst 흡수 경로 | 1000 TPS → Rate Limit ~900/userId per 5min cap → 재고 10 atomic DECR → Bulkhead 100 → Circuit Breaker 50% / 1s slow → DB |
| `ADR-007` §Decision | Resilience4j circuit breaker / bulkhead 임계값 |
| `ADR-013` §Out of Scope | k6 부하 테스트는 ADR-013 §의무 영역 외, 본 PR 은 별 영역 |

## Feature (k6 시나리오)

> Gherkin 변환 어려움 — k6 스크립트 시나리오 명세.

```javascript
// Scenario 1: [happy] 1000 TPS burst 1분 → SOLD_OUT 차단 + 재고 정확히 10 booking
// 30,000 요청 (1000 TPS × 30s) → 정확히 10 booking COMPLETED + 29,990 SOLD_OUT (409)

export const options = {
  scenarios: {
    midnight_burst: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '60s',  // 1분 burst
      preAllocatedVUs: 200,
      maxVUs: 500,
    },
  },
  thresholds: {
    // 정합성 — booking row 정확 10
    'http_req_failed{status:200}': ['count == 10'],
    // p99 응답 시간 < 1s (Bulkhead + Circuit Breaker 정합)
    'http_req_duration{status:200}': ['p(99) < 1000'],
    // 503 (Fail-Closed) 비율 < 1% (Redis 정상 가정)
    'http_req_failed{status:503}': ['rate < 0.01'],
  },
};
```

```gherkin
Scenario: [happy] 1000 TPS × 1분 burst → 재고 10 정확 + p99 < 1s + SOLD_OUT 99.9%
  Given stock=10, 사용자 5000 명 each 6 요청 (총 30000)
  When  k6 1000 TPS constant-arrival-rate × 60s 발사
  Then  정확히 10 booking COMPLETED (Req 1 정합성)
  And   29990 응답 = 409 SOLD_OUT
  And   p99 < 1000ms
  And   503 (Fail-Closed) rate < 1%

Scenario: [edge:boundary] burst window 5분 (300s × 1000 TPS = 30만 요청) → SOLD_OUT 30만 - 10
  Given stock=10
  When  k6 1000 TPS × 300s
  Then  정확히 10 booking COMPLETED + 29만9990 SOLD_OUT
  And   ADR-005 Rate Limit 도입 시 (feature-009 머지 후) — userId 별 ~900 cap → 시나리오 변경 검토

Scenario: [edge:failure] Redis 장애 시뮬레이션 — Sentinel failover 5~15s + 503 Fail-Closed
  Given burst 도중 Redis master 강제 다운
  When  failover 5~15s 동안 503 응답 발생
  Then  Sentinel failover 완료 후 정상 흐름 복구
  And   booking COMPLETED 0 oversell (재고 정합성 보장)

Scenario: [edge:concurrency] 같은 userId 동시 30 요청 (Rate Limit 부재 시) → 1 booking 만 idempotency 차단
  Given Rate Limit 미도입 상태 (feature-009 머지 전)
  When  같은 userId + 같은 idempotency-key 30 요청 동시
  Then  1 booking + 29 409 (PROCESSING) — feature-001 idempotency 정합
```

### Scenario Map

| # | Scenario | Type | k6 script | Status |
|---|---|---|---|---|
| 1 | 1000 TPS × 60s → 10 booking | happy | `load-test/midnight_burst_1min.js` | pending |
| 2 | 1000 TPS × 300s (burst window 전체) | edge:boundary | `load-test/midnight_burst_5min.js` | pending |
| 3 | Redis Sentinel failover 시나리오 | edge:failure | `load-test/sentinel_failover.js` | pending (운영 환경 필요 — 본 PR 미포함) |
| 4 | 같은 userId 동시 30 — idempotency | edge:concurrency | (이미 BookingIdempotencyConcurrencyTest 가 cover — k6 X) | done (Junit) |

## 핵심 결정 영역

| 영역 | 옵션 | 권장 |
|---|---|---|
| k6 vs Gatling | k6 (DECISIONS.md §결정의 한계 §5 — k6 권장) | 채택 |
| 실행 환경 | (a) 로컬 docker-compose / (b) staging 환경 / (c) CI 자동화 | (a) 로컬 우선 — staging 은 운영 진입 시점 |
| SLO threshold | 본 plan 의 thresholds 그대로 | 채택 — p99 < 1s, oversell 0, 503 < 1% |
| 재고 reset 전략 | 매 부하 테스트 시작 전 stock=10 reset | k6 setup() 또는 운영 admin API |
| 시간 의존 시나리오 (5분 burst) | 본 PR 단순화 — 1분 burst 만 GREEN, 5분 은 future | 1분 우선 |

## Phase

| Phase | 작업 |
|---|---|
| 3.1 | `load-test/midnight_burst_1min.js` 작성 (1000 TPS × 60s) — Scenario 1 |
| 3.2 | k6 docker-compose 정합 — `make k6-run` 또는 `k6 run` 명령 + 환경 변수 (BASE_URL, STOCK_RESET_API) |
| 3.3 | 부하 테스트 결과 보고서 작성 (`docs/load-test-report-YYYY-MM-DD.md`) — Scenario 1 결과 + thresholds 통과 여부 |
| 3.4 (future) | Scenario 2 (5분 burst) + Scenario 3 (Sentinel failover) 추가 |

## Out of Scope

- Sentinel failover 시나리오 (Scenario 3) — staging 환경 필요. 본 PR 미포함
- CI 자동화 — staging 환경 진입 후 (운영 단계)
- Gatling 통합 — k6 우선 (단순)
- 종단 부하 (전체 시스템 + DB read replica + CDN 등) — 본 PR scope 외

## Progress Log

- 2026-05-04 — Plan populated. Scenario 1 (1분 burst) 우선 + Scenario 2/3 deferred.

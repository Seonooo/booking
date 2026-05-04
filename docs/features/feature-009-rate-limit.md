# Feature 009: userId Token Bucket Rate Limit (ADR-005)

| Status | Owner | Created | Last Updated |
|---|---|---|---|
| Planning | TBD | 2026-05-04 | 2026-05-04 |

> **Self-contained (ADR-013).** 외부 대화 참조 금지.

## Request

> 사용자 *"a, c, b 순서"* — feature-008 머지 (운영 진입 가능 단계 a) → feature-010 k6 부하 테스트 (단계 c) → 본 feature-009 (단계 b). 운영 보강 영역 — REQUIREMENTS Req 1 *공정성* 본격 보강 (봇 차단 + 본인인증 외 다층 방어).

## 적용 결정 (SSOT)

| 출처 | 결정 |
|---|---|
| `ADR-005` §Decision | userId 기반 Token Bucket — 초당 3 토큰 + burst 5. Redis Lua atomic. 진입(ADR-008) **전** 검사 |
| `ADR-005` §위협 시나리오 | 시나리오 B (단일 봇 1000회) 결정적 차단 + 시나리오 C (분산 봇) 부분 차단 |
| `DECISIONS.md` §10 §방어 시퀀스 | Layer 1 = Rate Limit (Tomcat 진입 직후, stock counter 전) |
| `ADR-002` Lua atomic | Token Bucket 의 *조회 + 차감* 단일 atomic |
| `ADR-007` Fail-Closed | Redis 장애 시 Rate Limit 우회 → ADR-007 §Fail-Closed 정합 검토 (본 PR 결정 영역) |

## Feature

```gherkin
Background:
  Given 사용자 1001이 인증된 상태
  And   Redis rate:user:1001 키 초기화 (또는 미존재)

Scenario: [happy] 첫 요청 → 토큰 5 → 1 차감 → 통과 (200 또는 후속 흐름)
  Given Redis rate:user:1001 미존재 (default tokens=5)
  When  사용자가 POST /booking 호출
  Then  Lua tryAcquire 통과 (return 1) → 후속 흐름 진입
  And   Redis rate:user:1001 의 tokens=4

Scenario: [edge:boundary] 5 burst 토큰 모두 사용 → 6번째 요청 즉시 → 429
  Given Redis rate:user:1001 의 tokens=0
  When  사용자가 POST /booking 호출 (refill 1초 미만)
  Then  HTTP 429 Too Many Requests
  And   응답 메시지에 "요청이 너무 많습니다"

Scenario: [happy] burst 소진 후 1초 대기 → refill 3 토큰 → 통과
  Given Redis rate:user:1001 tokens=0, last_refill=now-1100ms
  When  사용자가 POST /booking 호출
  Then  Lua refill 계산: floor(1100ms * 3/1000) = 3 → tokens 3, 1 차감 → 2 → 통과

Scenario: [edge:concurrency] 같은 userId 100 동시 요청 → 정확히 5 통과 + 95 차단
  Given 같은 userId 1001 동시 100 요청 (burst 5 가정)
  When  ExecutorService 100 thread 동시 발사
  Then  정확히 5 요청 통과 (200 또는 후속) + 95 요청 429
  And   token count 정확 (oversell 0건 — Lua atomic 검증)

Scenario: [edge:failure] Redis 장애 → ADR-007 Fail-Closed 정합 — Rate Limit 우회 vs 503?
  Given Redis 장애 시뮬레이션 (Resilience4j circuit OPEN)
  When  사용자가 POST /booking 호출
  Then  본 PR 결정 영역 — *권장 ADR-005 amendment*: Rate Limit fallback Fail-Open (Rate Limit 우회 + 후속 흐름 진입)
  Note  ADR-005 가 *진입 통제 (ADR-008)* 의 *보조 계층* — 진입 통제 자체는 Fail-Closed 유지. Rate Limit 만 Fail-Open 가능 (다층 방어 정합)
```

### Scenario Map

| # | Type | Test Method | File | Status |
|---|---|---|---|---|
| 1 | happy | `should_pass_when_first_request_with_full_tokens` | `RateLimitIntegrationTest` | pending |
| 2 | edge:boundary | `should_return_429_when_burst_exhausted` | `RateLimitIntegrationTest` | pending |
| 3 | happy | `should_refill_tokens_after_wait` | `RateLimitIntegrationTest` | pending |
| 4 | edge:concurrency | `should_block_99_when_100_concurrent_requests_with_burst_5` | `RateLimitConcurrencyTest` | pending |
| 5 | edge:failure | `should_apply_fail_open_when_redis_unavailable` | `RateLimitIntegrationTest` | pending (단 ADR-005 amendment 결정 후) |

## 핵심 결정 영역

| 영역 | 옵션 | 권장 |
|---|---|---|
| HTTP status code | (a) **429 Too Many Requests** / (b) 503 Service Unavailable | (a) — *클라이언트 측 비정상 행동* 시맨틱 정합 |
| Redis 장애 시 fallback | (i) Fail-Closed 503 (ADR-007 정합) / (ii) Fail-Open 우회 (다층 방어 — 진입 통제가 본 ADR-008 정합) | **(ii) Fail-Open** — Rate Limit 은 *보조 계층*, 진입 통제 (ADR-008 stock counter) 가 *주 계층*. ADR-005 amendment 트리거 필요 |
| 검사 위치 | (1) Filter (Spring Security 또는 OncePerRequestFilter) / (2) Controller @AOP / (3) BookingService 진입 직후 | **(1) Filter** — Tomcat 스레드 점유 최소화 (Bulkhead 정합) |
| Token Bucket 식별자 | userId — `rate:user:{userId}` (ADR-005) | 채택 |
| Refill 정책 | 초당 3 + burst 5 (ADR-005) | 채택 |

## Phase

| Phase | 작업 |
|---|---|
| 3.1 | Lua `rate_limit.lua` (Token Bucket atomic) + `RateLimiter` port + `RedisRateLimiterAdapter` (Resilience4j wrapping, Fail-Open fallback) |
| 3.2 | `RateLimitFilter` (`OncePerRequestFilter`) — 진입 시점 검사 + 429 응답. POST /booking 만 적용 (GET /checkout 제외) |
| 3.3 | `GlobalExceptionHandler` 에 `RateLimitedException` → 429 매핑 (또는 Filter 가 직접 응답) |
| 3.4 | Test 5 시나리오 GREEN |

## ADR-005 amendment 트리거

본 plan 의 *Fail-Open Redis 장애 fallback* 결정은 ADR-007 *Fail-Closed 통일* 와 충돌. amendment 트리거:

- ADR-005 §Decision 에 *"Redis 장애 시 fallback = Fail-Open (Rate Limit 우회). 진입 통제 (ADR-008) 자체는 Fail-Closed 유지"* 명시
- ADR-007 §Decision 에 amendment — *"Fail-Closed 통일은 진입 통제 / 멱등성 / Saga 등 무결성 영역. Rate Limit 같은 보조 계층은 Fail-Open 가능"*

본 amendment 는 본 PR 진입 시점에 별 commit 분리.

## Out of Scope

- IP 기반 Rate Limit (ADR-005 §옵션 D — NAT 오차단 위험)
- 본인인증 도메인 — 운영 환경 가정 (DECISIONS.md §결정의 한계)
- Spring Security 통합 — 본 PR 은 단순 Filter
- @RateLimited annotation (선언적) — 본 PR 단순 Filter, declarative 는 future

## Progress Log

- 2026-05-04 — Plan populated. ADR-005 amendment 트리거 (Fail-Open) + Filter 위치 + 429 응답 결정.

# ADR-2026-05-01-005: userId 기반 Token Bucket Rate Limiting을 큐 입장 전 1차 완화 계층으로 도입

## Status

Accepted

## Context

ADR-004에서 공정성을 *"약한 동등 시도 기회를 갖는 선착순"*으로 정의했다. 이 정의의 약점은 봇/매크로가 동시 다발 요청으로 진입(ADR-008)의 상당수를 점유할 가능성을 구조적으로 차단하지 못한다는 점이다. 봇 1개가 자정 시각에 30개 요청을 동시 발사하면 진입 대부분을 차지할 수 있다.

근본 해결책은 본인인증 + 1인 1요청 제한이다. 그러나 본 시스템 범위에서 회원 인증 및 로그인 보안 처리는 제외되었다. 따라서 본 시스템은 **운영 환경에서는 본인인증이 적용된다는 가정 하에**, 현 범위에서 가능한 1차 완화 계층으로 Rate Limiting을 도입한다.

Rate Limit의 책임 범위를 명확히 할 필요가 있다. 1000 TPS의 burst 트래픽 자체를 막는 것은 진입 통제(ADR-008)의 역할이고, Rate Limit의 역할은 **단일 식별자의 비정상 행동을 차단**하는 것이다. 두 메커니즘은 다른 위협을 다루며, 계층 방어(Defense in Depth) 구조를 이룬다.

위협 시나리오별 대응 매핑:

| 시나리오 | 트래픽 패턴 | 대응 계층 |
|---|---|---|
| A. 1000명의 정상 사용자 동시 시도 | 1000 userId × 각 1회 | 진입 통제 (ADR-008) |
| B. 봇 1개가 같은 userId로 1000회 시도 | 1 userId × 1000회 | Rate Limiting (본 ADR) |
| C. 봇 100개가 분산 (각 다른 userId) | 100 userId × 각 10회 | Rate Limiting 부분 차단 |
| D. 봇이 1000개 계정 만들어 각 1회 | 1000 userId × 각 1회 | 본인인증 (운영 환경 가정) |

## Options Considered

| 옵션 | Pros | Cons | 비용 |
|---|---|---|---|
| A. Rate Limit 미도입 | 구현 단순 | 시나리오 B 무방비, 단일 봇이 진입 다수 점유 가능 | 보안 비용 ↑↑ |
| B. IP 기반 Rate Limit | 익명 봇도 차단 가능 | 모바일/회사 NAT으로 정상 사용자 대량 오차단 위험, 자정 트래픽은 모바일 비중 높음 | 운영 비용 ↑ (오차단 CS 대응) |
| C. **userId 기반 Token Bucket Rate Limit** | NAT 문제 없음, 본인인증과 결합 시 시나리오 B/C/D 모두 커버 | userId 신뢰성이 본인인증 전제에 의존 | 구현 비용 중간, 본인인증 인프라 의존 |
| D. userId + IP 다층 Rate Limit | 가장 강력 | NAT 오차단 위험 + 구현 복잡도 ↑ + 두 임계값 튜닝 필요 | 구현/운영 비용 ↑↑ |

## Decision

**옵션 C 채택. userId 기반 Token Bucket Rate Limiting을 진입 전 1차 완화 계층으로 도입한다.**

구체 설계:
- **식별자**: userId (운영 환경에서 본인인증을 거친 신뢰 가능한 ID로 가정)
- **알고리즘**: Token Bucket
- **임계값**: 초당 3 토큰 생성, burst 5 토큰 허용
- **위치**: 재고 카운터 진입(ADR-008) **전**에 검사 — 봇 트래픽이 진입 시도 자체를 도배하는 것을 1차 차단
- **구현**: Redis Lua Script (ADR-002와 동일 패턴, atomic 보장)

```lua
-- KEYS[1]: rate limit key (e.g., "rate:user:{userId}")
-- ARGV[1]: max tokens (5)
-- ARGV[2]: refill rate per second (3)
-- ARGV[3]: current timestamp ms
-- Returns: 1 = allowed, 0 = rate limited

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or tonumber(ARGV[1])
local last_refill = tonumber(bucket[2]) or tonumber(ARGV[3])

-- 토큰 보충 계산
local elapsed_ms = tonumber(ARGV[3]) - last_refill
local refilled = math.floor(elapsed_ms * tonumber(ARGV[2]) / 1000)
tokens = math.min(tonumber(ARGV[1]), tokens + refilled)

if tokens < 1 then
    redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', ARGV[3])
    redis.call('EXPIRE', KEYS[1], 60)
    return 0
end

tokens = tokens - 1
redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', ARGV[3])
redis.call('EXPIRE', KEYS[1], 60)
return 1
```

핵심 trade-off:
- **Buy:** 시나리오 B(단일 봇의 동시 다발 요청)의 결정적 차단. 본인인증과 결합 시 시나리오 D까지 계층적으로 커버. NAT 오차단 위험 회피.
- **Pay:** userId의 신뢰성이 운영 환경의 본인인증에 의존한다. 본인인증이 없는 환경에서는 봇이 다수 계정을 만들어 시나리오 D로 우회할 수 있다.

임계값(초당 3, burst 5)을 택한 근거:
- 사람의 우발적 더블클릭/새로고침은 burst 5로 흡수.
- 매크로의 동시 다발(예: 1초당 30회) 시도는 즉시 차단되어 점유율이 1/10로 떨어진다.
- 5분 burst window 기준 userId당 최대 약 900회 시도 허용 — 정상 사용자에겐 충분, 봇에겐 무의미한 수준.

옵션 B(IP 기반)를 채택하지 않은 이유: 자정 트래픽의 모바일 비중을 고려할 때 통신사 NAT 환경에서 정상 사용자 오차단 위험이 크다. 임계값을 NAT 환경에 맞게 보수적으로 잡으면 봇 차단 효과가 약해져 도입 의미가 사라진다.

옵션 D(다층)는 현 범위에서 over-engineering으로 판단해 향후 확장 시 검토로 남긴다.

### Redis 장애 시 정책

ADR-007의 Fail-Closed 정책에 따라, Redis 장애 시 Rate Limit도 503 응답을 반환한다. 일반 API 산업 권장(Fail-Open)과 다른 결정이며, 그 정당화는 ADR-007에 박혀있다.

## Consequences

**긍정 결과**
- 시나리오 B(단일 봇 동시 다발)는 결정적으로 차단된다. 봇 1개가 5번 이상 진입 시도하지 못한다.
- 본인인증이 적용된 운영 환경에서는 시나리오 D(다수 계정 분산 공격)가 본인인증 계층에서 막혀, 전체 봇 위협이 ADR-005 + 본인인증으로 다층 차단된다.
- 진입 전 단계에서 차단되므로, 진입 카운터의 정합성(ADR-008)에 영향을 주지 않는다.
- Redis Lua Script 사용으로 ADR-002와 동일한 운영 패턴을 재사용한다.

**부정 결과**
- userId의 신뢰성이 본인인증 인프라에 의존한다. 본인인증이 우회되거나 계정 양산이 가능한 환경에서는 효력이 약화된다.
- Rate Limit Lua Script는 Token Bucket 계산이 포함되어 ADR-002의 진입 스크립트보다 약간 무겁다(HMGET + 산술 + HSET). Redis 단일 스레드 점유 시간을 모니터링해야 한다.
- 정상 사용자도 새로고침을 5회 이상 빠르게 누르면 일시 차단된다. CS 문의 가능성 존재.
- 본인인증이 없는 환경에서는 시나리오 D(계정 양산 봇)에 무방비다. 이는 현 범위 한계로 명시적으로 인정한다.

**재검토 시점**
- 본인인증 인프라가 변경되거나 약화될 때 (Rate Limit 유효성 재평가)
- 정상 사용자의 임계값 trigger 비율이 1% 이상 관찰될 때 (임계값 완화 검토)
- 봇 트래픽이 IP 단에서 다수 IP로 분산되는 패턴이 관찰될 때 (옵션 D — 다층 Rate Limit 도입 검토)
- 진입 점유율이 비정상적으로 빠르게 차는 패턴이 관찰될 때 (CAPTCHA 등 추가 계층 검토)

# ADR-2026-05-01-002: 큐 입장은 Redis Lua 스크립트로 atomic 처리

## Status

Accepted

> **Note**: 본 ADR은 ADR-001(deprecated)의 큐 입장에 적용되었으나, 패턴 자체(Lua atomic)는 ADR-008의 재고 카운터 DECR에 그대로 재사용된다. 따라서 본 ADR은 여전히 active이며, ADR-001의 deprecation에 영향받지 않는다.

## Context

ADR-001에서 큐 길이를 30명으로 제한하기로 결정했다. 이 제한은 *"큐가 가득 찼는지 검사"*와 *"큐에 추가"* 두 연산이 atomic하게 수행될 때만 보장된다. 1000 TPS 환경에서 이 두 연산을 분리해서 수행하면 race condition으로 31, 32명이 큐에 들어가는 상황이 반드시 발생한다.

Redis는 단일 스레드 모델이므로 명령 단위로는 atomic하지만, 여러 명령을 조합한 트랜잭션이 필요한 경우 별도 메커니즘이 요구된다. 선택지는 Redis 트랜잭션(MULTI/EXEC + WATCH), Lua 스크립트, 분산 락(Redlock 등)이다.

본 atomic 처리 패턴은 후속 ADR에서도 재사용된다. ADR-008의 재고 카운터 DECR도 동일한 *"검사 + 변경"* 패턴이며, ADR-002의 결정을 그대로 적용한다.

## Options Considered

| 옵션 | Pros | Cons | 비용 |
|---|---|---|---|
| A. ZCARD 후 ZADD (분리된 명령) | 단순, 클라이언트 코드만으로 작성 | 1000 TPS에서 race condition 확정적 발생, 큐 한도 초과 입장 | 구현 비용 최저, 정합성 비용 ↑↑ |
| B. MULTI/EXEC + WATCH (Optimistic Lock) | 표준 Redis 트랜잭션 | WATCH 충돌 시 클라이언트 재시도 필요, 1000 TPS에서 재시도 폭주 | 구현 비용 중간, 재시도 로직 필요 |
| C. **Lua Script** | atomic 단일 round-trip, 재시도 불필요, Redis 6.2+ 표준 | 디버깅 난이도 약간 증가, Lua 문법 학습 필요 | 구현 비용 중간, 운영 비용 낮음 |
| D. Redlock 등 분산 락 | 강한 일관성 보장 | 큐 입장 단순 검사에는 과한 도구, 락 획득/해제 round-trip 추가 | 구현 비용 높음, 지연 ↑ |

## Decision

**옵션 C 채택. Redis Lua 스크립트로 *"검사 + 변경"* 연산을 atomic하게 처리한다.**

원본 큐 입장 스크립트 (ADR-001 시점):

```lua
-- KEYS[1]: queue key (e.g., "queue:accommodation:123")
-- ARGV[1]: max queue size (30)
-- ARGV[2]: score (timestamp ms)
-- ARGV[3]: member (ticket id)
-- Returns: 1 = entered, 0 = full

local current = redis.call('ZCARD', KEYS[1])
if current >= tonumber(ARGV[1]) then
    return 0
end
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
redis.call('EXPIRE', KEYS[1], 600)  -- 10분 TTL (보호용)
return 1
```

ADR-008로 전환 후 동일 패턴이 재고 카운터 DECR에 적용된다 (ADR-008 본문 참조).

핵심 trade-off:
- **Buy:** 단일 round-trip atomic 처리, 재시도 로직 불필요, 1000 TPS에서도 race condition 0건 보장.
- **Pay:** Lua 스크립트는 Redis 단일 스레드를 점유하는 시간 동안 다른 명령을 블록한다. 따라서 스크립트는 가능한 한 짧게 유지해야 하며 (현재 3개 명령), 복잡한 비즈니스 로직을 Lua로 옮기지 않도록 주의해야 한다.

WATCH/MULTI를 택하지 않은 이유: 1000 TPS 환경에서는 거의 모든 트랜잭션이 WATCH 충돌로 재시도에 들어가, 결과적으로 Lua보다 더 많은 round-trip과 더 큰 지연을 만든다.

## Consequences

**긍정 결과**
- 큐 길이 한도 또는 재고 카운터가 정확히 보장된다 (race condition 0).
- 클라이언트 코드는 단일 EVAL 호출로 단순화된다.
- Redis 6.2+ 표준 기능이므로 추가 인프라 없음.
- 후속 ADR(ADR-008)에서 동일 패턴 재사용으로 구현 일관성 확보.

**부정 결과**
- Lua 스크립트는 운영 시 SCRIPT LOAD / EVALSHA 캐싱을 신경 써야 한다(매번 EVAL은 네트워크 비용 ↑).
- 스크립트 수정 시 SHA가 바뀌므로 모든 애플리케이션 인스턴스의 캐시 무효화를 고려해야 한다.
- Lua 내부 에러는 일반 Java 예외 스택과 분리되어 디버깅이 까다롭다.

**재검토 시점**
- Redis Cluster로 전환 시 (Lua 스크립트의 키는 모두 같은 슬롯에 있어야 함, 키 설계 재검토 필요)
- atomic 처리가 필요한 연산이 추가될 때 (Lua 스크립트 라이브러리화 검토)
- Lua 스크립트가 5개 명령 이상으로 복잡해질 때 (Redis Function 도입 검토 — Redis 7.0+)

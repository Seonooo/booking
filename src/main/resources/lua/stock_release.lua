-- Stock atomic release (ADR-008 amendment / ADR-002 atomic).
--
-- KEYS[1]: stock key (e.g., "stock:accommodation:42")
-- ARGV[1]: hold key (e.g., "hold:user:1001:product:42")
-- Returns: {1} = released (INCR + DEL hold), {0} = no hold key (idempotent — 이미 release 됐거나 만료)
--
-- Idempotent release — hold key EXISTS 일 때만 INCR + DEL. 두 worker 가 동시 release 시도해도
-- 두 번째는 EXISTS == 0 → INCR 미수행 → over-INCR 차단.
--
-- 본 스크립트는 sweeper (feature-006) / Saga 보상 (feature-005) / Reconciliation (feature-007)
-- 모두 사용.

if redis.call('EXISTS', ARGV[1]) == 0 then
    return {0}
end

redis.call('INCR', KEYS[1])
redis.call('DEL', ARGV[1])
return {1}

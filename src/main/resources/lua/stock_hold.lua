-- Stock atomic hold (ADR-008 §진입 로직 / ADR-002 atomic).
--
-- KEYS[1]: stock key (e.g., "stock:accommodation:42")
-- ARGV[1]: hold key (e.g., "hold:user:1001:product:42")
-- ARGV[2]: TTL seconds (string, 300 = 5분)
-- Returns: {1} = entered, {0} = sold out or already held by same user
--
-- 본 스크립트는 ADR-008 §진입 로직 의 원본 그대로다.
-- ADR-008 amendment (2026-05-04) 후 의미: hold key EXISTS 시 차단의 의미는 *"booking
-- COMPLETED 사용자 재진입 차단"* 으로 재정의. 결제 실패 후 재시도 흐름은 본 feature
-- 미포함 (Saga+Outbox feature 영역) — 본 PR 의 happy path 한정 의미는 *"같은 사용자가
-- 이미 한 번 진입했다"* 단순 차단.

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return {0}
end

if redis.call('EXISTS', ARGV[1]) == 1 then
    return {0}
end

redis.call('DECR', KEYS[1])
redis.call('SET', ARGV[1], 'HOLD', 'EX', ARGV[2])
return {1}

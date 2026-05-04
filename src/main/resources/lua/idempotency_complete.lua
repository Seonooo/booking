-- Idempotency mark-completed (atomic, ADR-006 §흐름).
--
-- Caller has already committed the DB transaction. This script flips the
-- Redis key from PROCESSING:<hash> to COMPLETED:<hash>:<responseJson>
-- so subsequent requests served from Redis return the cached response.
--
-- KEYS[1] = "idempotency:{uuid}"
-- ARGV[1] = bodyHash (must match the stored PROCESSING hash; otherwise no-op)
-- ARGV[2] = responseJson (already serialized to a single-line JSON string)
-- ARGV[3] = ttlSeconds (re-applied on the new value)
--
-- Returns:
--   {"OK"}                    — transitioned PROCESSING -> COMPLETED.
--   {"NOT_PROCESSING", val}   — key was missing, malformed, or already
--                               COMPLETED, or hash differs. No write happens.

local val = redis.call('GET', KEYS[1])
if not val then
    return {"NOT_PROCESSING", ""}
end

local sep = string.find(val, ':')
if not sep then
    return {"NOT_PROCESSING", val}
end

local status = string.sub(val, 1, sep - 1)
local stored_hash = string.sub(val, sep + 1)

if status ~= "PROCESSING" then
    return {"NOT_PROCESSING", val}
end
if stored_hash ~= ARGV[1] then
    return {"NOT_PROCESSING", val}
end

local new_val = "COMPLETED:" .. stored_hash .. ":" .. ARGV[2]
redis.call('SET', KEYS[1], new_val, 'EX', tonumber(ARGV[3]))
return {"OK"}

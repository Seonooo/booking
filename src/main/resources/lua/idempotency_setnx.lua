-- Idempotency check + reserve (atomic, ADR-002 / ADR-006).
--
-- KEYS[1] = "idempotency:{uuid}"
-- ARGV[1] = incomingBodyHash (SHA-256 hex, 64 chars)
-- ARGV[2] = ttlSeconds (default 900)
--
-- Returns:
--   {"NEW"}                                        Key did not exist; PROCESSING
--                                                  has just been registered.
--   {"PROCESSING", storedHash}                     Key exists in PROCESSING with
--                                                  the same hash.
--   {"COMPLETED", storedHash, responseJson}        Key already finished; cached
--                                                  response is returned (responseJson
--                                                  may be the empty string when none).
--   {"HASH_MISMATCH", storedHash}                  Same key but the stored body
--                                                  hash differs (tampering signal).
--
-- Stored value layout:
--   "PROCESSING:<hash>"                or
--   "COMPLETED:<hash>:<responseJson>"   (responseJson may itself contain ':')

local val = redis.call('GET', KEYS[1])

if not val then
    -- Key absent → SET NX. If we lose the race, fall through and re-read.
    local stored = "PROCESSING:" .. ARGV[1]
    local set_ok = redis.call('SET', KEYS[1], stored, 'EX', tonumber(ARGV[2]), 'NX')
    if set_ok then
        return {"NEW"}
    end
    val = redis.call('GET', KEYS[1])
    if not val then
        -- Extremely rare: someone deleted between SET NX miss and re-GET. Treat as NEW retry.
        return {"NEW"}
    end
end

-- Parse "<status>:<hash>[:<rest>]"
local sep1 = string.find(val, ':')
if not sep1 then
    return {"HASH_MISMATCH", ""}
end
local status = string.sub(val, 1, sep1 - 1)
local rest = string.sub(val, sep1 + 1)

-- Second separator splits hash and (optional) response payload.
local sep2 = string.find(rest, ':')
local stored_hash
local response_json
if sep2 then
    stored_hash = string.sub(rest, 1, sep2 - 1)
    response_json = string.sub(rest, sep2 + 1)
else
    stored_hash = rest
    response_json = nil
end

if stored_hash ~= ARGV[1] then
    return {"HASH_MISMATCH", stored_hash}
end

if status == "PROCESSING" then
    return {"PROCESSING", stored_hash}
end

-- COMPLETED — return cached response (empty string when nil for protocol simplicity).
return {"COMPLETED", stored_hash, response_json or ""}

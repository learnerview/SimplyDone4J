local key = KEYS[1]
local now = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])
local windowStart = now - windowMs

-- Remove expired entries
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

-- Count remaining entries
local count = redis.call('ZCARD', key)

if count >= maxRequests then
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    if oldest and #oldest >= 2 then
        local oldestScore = tonumber(oldest[2])
        return {0, oldestScore}
    end
    return {0, windowStart}
end

-- Add current timestamp
redis.call('ZADD', key, now, now)
-- Return 1 (allowed) and the oldest timestamp in window
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local oldestScore = windowStart
if oldest and #oldest >= 2 then
    oldestScore = tonumber(oldest[2])
end
return {1, oldestScore}

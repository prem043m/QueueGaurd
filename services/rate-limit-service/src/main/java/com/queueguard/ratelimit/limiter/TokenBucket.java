package com.queueguard.ratelimit.limiter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Distributed token-bucket rate limiter backed by an atomic Redis Lua script.
 * All arithmetic happens inside Redis, so concurrent checks from many gateway
 * replicas can never over-admit traffic.
 */
@Component
public class TokenBucket {

    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            local delta = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + delta * refill)
            local allowed = 0
            local retryMs = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            else
              retryMs = math.ceil((cost - tokens) / refill * 1000.0)
            end
            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            local ttl = math.ceil(math.max(capacity / math.max(refill, 0.001) * 2000.0, 60000.0))
            redis.call('PEXPIRE', key, ttl)
            return {allowed, math.floor(tokens * 1000), retryMs}
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);

    public TokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Result(boolean allowed, double remainingTokens, long retryAfterMs) {
    }

    /** Attempts to consume {@code cost} tokens from the bucket identified by {@code key}. */
    @SuppressWarnings("unchecked")
    public Result consume(String key, double capacity, double refillPerSec, long cost) {
        long nowMs = System.currentTimeMillis();
        List<?> res = redis.execute(script, List.of(key),
                String.valueOf(capacity), String.valueOf(refillPerSec),
                String.valueOf(nowMs), String.valueOf(cost));
        if (res == null || res.size() < 3) {
            // Fail open with a single token: Redis issues must not black-hole traffic.
            return new Result(true, 0, 0);
        }
        boolean allowed = ((Number) res.get(0)).longValue() == 1L;
        double remaining = ((Number) res.get(1)).longValue() / 1000.0;
        long retryMs = ((Number) res.get(2)).longValue();
        return new Result(allowed, remaining, retryMs);
    }
}

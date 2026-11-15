package com.queueguard.queue.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis sorted-set ready-queue. The score encodes priority first, then readiness time,
 * so ZPOPMIN always yields the most urgent message that is due.
 *
 * score = (9 - priority) * 10^13 + readyAtEpochMillis
 */
@Component
public class RedisReadyQueue {

    private static final long PRIORITY_SPAN = 10_000_000_000_000L;

    private final StringRedisTemplate redis;

    public RedisReadyQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String queue) {
        return "qg:ready:" + queue;
    }

    public static double score(int priority, Instant readyAt) {
        long p = Math.max(0, Math.min(9, priority));
        return (9L - p) * PRIORITY_SPAN + readyAt.toEpochMilli();
    }

    /** Enqueue a message id (re-adding is harmless and self-healing). */
    public void push(String queue, String messageId, int priority, Instant readyAt) {
        redis.opsForZSet().add(key(queue), messageId, score(priority, readyAt));
    }

    /** Enqueue only if not already present in the ready set. */
    public void pushIfAbsent(String queue, String messageId, int priority, Instant readyAt) {
        redis.opsForZSet().addIfAbsent(key(queue), messageId, score(priority, readyAt));
    }

    /** Atomically pop up to {@code n} of the most urgent message ids (ZPOPMIN). */
    public List<String> pop(String queue, int n) {
        List<String> ids = new ArrayList<>();
        ZSetOperations<String, String> zset = redis.opsForZSet();
        for (int i = 0; i < n; i++) {
            ZSetOperations.TypedTuple<String> tuple = zset.popMin(key(queue));
            if (tuple == null || tuple.getValue() == null) {
                break;
            }
            ids.add(tuple.getValue());
        }
        return ids;
    }

    /** Remove a message id from the ready set (e.g. when dead-lettered). */
    public void remove(String queue, String messageId) {
        redis.opsForZSet().remove(key(queue), messageId);
    }

    public long depth(String queue) {
        Long size = redis.opsForZSet().size(key(queue));
        return size == null ? 0 : size;
    }

    /** Drain a few raw ids for diagnostics. */
    public Set<String> peek(String queue, int n) {
        var tuples = redis.opsForZSet().rangeWithScores(key(queue), 0, n - 1L);
        Set<String> out = new java.util.LinkedHashSet<>();
        if (tuples != null) {
            for (var t : tuples) {
                if (t.getValue() != null) {
                    out.add(t.getValue());
                }
            }
        }
        return out;
    }
}

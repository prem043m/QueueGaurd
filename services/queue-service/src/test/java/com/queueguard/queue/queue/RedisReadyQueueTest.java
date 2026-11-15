package com.queueguard.queue.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisReadyQueueTest {

    @Test
    void scorePrioritizesUrgencyBeforeReadyTime() {
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant muchLater = early.plusSeconds(3600);

        assertTrue(RedisReadyQueue.score(9, muchLater) < RedisReadyQueue.score(1, early));
        assertTrue(RedisReadyQueue.score(5, early) < RedisReadyQueue.score(5, muchLater));
    }
}

package com.queueguard.qms.service;

import static org.mockito.Mockito.*;

import com.queueguard.qms.service.OutboxEventLifecycleService.FailureDisposition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

class OutboxPublisherTest {
    private final OutboxEventLifecycleService lifecycle =
            mock(OutboxEventLifecycleService.class);
    private final RedisStreamPublisher streamPublisher = mock(RedisStreamPublisher.class);
    private final QmsEventMetrics metrics = new QmsEventMetrics(new SimpleMeterRegistry());
    private final OutboxPublisher publisher =
            new OutboxPublisher(lifecycle, streamPublisher, metrics, Tracer.NOOP);

    @Test
    void retriesRedisFailuresAndContinuesWithTheRestOfTheClaimedBatch() {
        ClaimedOutboxEvent failed = event(1L);
        ClaimedOutboxEvent timedOut = event(2L);
        ClaimedOutboxEvent successful = event(3L);
        RedisConnectionFailureException redisFailure =
                new RedisConnectionFailureException("Redis unavailable");
        QueryTimeoutException redisTimeout = new QueryTimeoutException("Redis timeout");
        when(lifecycle.claimBatch()).thenReturn(List.of(failed, timedOut, successful));
        doThrow(redisFailure).when(streamPublisher).publish(failed);
        doThrow(redisTimeout).when(streamPublisher).publish(timedOut);
        when(lifecycle.recordFailure(failed, redisFailure))
                .thenReturn(FailureDisposition.RETRY_SCHEDULED);
        when(lifecycle.recordFailure(timedOut, redisTimeout))
                .thenReturn(FailureDisposition.RETRY_SCHEDULED);
        when(lifecycle.markPublished(successful)).thenReturn(true);

        publisher.publishBatch();

        verify(lifecycle).recordFailure(failed, redisFailure);
        verify(lifecycle).recordFailure(timedOut, redisTimeout);
        verify(streamPublisher).publish(successful);
        verify(lifecycle).markPublished(successful);
    }

    @Test
    void databaseFailureDuringClaimDoesNotAttemptRedisPublication() {
        when(lifecycle.claimBatch())
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL unavailable"));

        publisher.publishBatch();

        verifyNoInteractions(streamPublisher);
    }

    @Test
    void databaseFailureAfterRedisAcceptsLeavesClaimForLeaseRecovery() {
        ClaimedOutboxEvent event = event(4L);
        when(lifecycle.claimBatch()).thenReturn(List.of(event));
        doReturn(null).when(streamPublisher).publish(event);
        when(lifecycle.markPublished(event))
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL unavailable"));

        publisher.publishBatch();

        verify(streamPublisher).publish(event);
        verify(lifecycle).markPublished(event);
        verify(lifecycle, never()).recordFailure(any(), any());
    }

    @Test
    void retryDelayUsesExponentialBackoffAndHonorsTheConfiguredCap() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties(
                true, "qg:test", 10, 1000, 6, 250, 700, 30000);

        org.junit.jupiter.api.Assertions.assertEquals(250, properties.retryDelayMillis(1));
        org.junit.jupiter.api.Assertions.assertEquals(500, properties.retryDelayMillis(2));
        org.junit.jupiter.api.Assertions.assertEquals(700, properties.retryDelayMillis(3));
        org.junit.jupiter.api.Assertions.assertEquals(700, properties.retryDelayMillis(6));
    }

    private static ClaimedOutboxEvent event(Long id) {
        return new ClaimedOutboxEvent(
                id, 1L, "InterviewEvaluation", id, "EVALUATION_SUBMITTED",
                "{}", Instant.parse("2026-01-01T00:00:00Z"),
                "evaluation-submitted:1:" + id, UUID.randomUUID(), 1);
    }
}

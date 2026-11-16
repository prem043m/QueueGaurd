package com.queueguard.qms.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.queueguard.qms.domain.OutboxEvent;
import com.queueguard.qms.domain.OutboxEventStatus;
import com.queueguard.qms.repo.OutboxEventRepository;
import com.queueguard.qms.service.ClaimedOutboxEvent;
import com.queueguard.qms.service.OutboxEventLifecycleService;
import com.queueguard.qms.service.OutboxEventLifecycleService.FailureDisposition;
import com.queueguard.qms.service.OutboxPublisher;
import com.queueguard.qms.service.OutboxPublisherProperties;
import com.queueguard.qms.service.RedisStreamPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "queueguard.outbox.enabled=true",
        "queueguard.outbox.stream-key=qg:test:qms:outbox",
        "queueguard.outbox.batch-size=1",
        "queueguard.outbox.polling-interval-ms=3600000",
        "queueguard.outbox.max-attempts=3",
        "queueguard.outbox.initial-retry-delay-ms=100",
        "queueguard.outbox.max-retry-delay-ms=1000",
        "queueguard.outbox.lease-duration-ms=1000"
})
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublisherPostgresRedisTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_outbox_test")
                    .withUsername("qms_test")
                    .withPassword("qms_test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxEventRepository eventRepository;
    @Autowired private OutboxEventLifecycleService lifecycle;
    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxPublisherProperties properties;
    @Autowired private RedisStreamPublisher streamPublisher;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void clearState() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
        redis.delete(properties.streamKey());
    }

    @Test
    void publishesStableEventEnvelopeAndMarksItPublished() {
        OutboxEvent event = newEvent("EVALUATION_SUBMITTED", 701L);
        OutboxEvent persistedEvent = eventRepository.findById(event.getId()).orElseThrow();

        publisher.publishBatch();

        OutboxEvent published = eventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getProcessingStatus());
        assertNotNull(published.getPublishedAt());
        assertNull(published.getClaimToken());
        assertEquals(1, published.getRetryCount());

        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(properties.streamKey(), Range.unbounded());
        assertEquals(1, records.size());
        MapRecord<String, Object, Object> record = records.get(0);
        assertEquals(event.getId().toString(), record.getValue().get("eventId"));
        assertEquals(event.getEventType(), record.getValue().get("eventType"));
        assertEquals(event.getOrganizationId().toString(),
                record.getValue().get("organizationId"));
        assertEquals(event.getAggregateType(), record.getValue().get("aggregateType"));
        assertEquals(event.getAggregateId().toString(), record.getValue().get("aggregateId"));
        assertEquals(event.getPayload(), record.getValue().get("payload"));
        assertEquals(persistedEvent.getCreatedAt().toString(),
                record.getValue().get("createdAt"));
        assertEquals(event.getIdempotencyKey(), record.getValue().get("idempotencyKey"));

        publisher.publishBatch();
        assertEquals(1, redis.opsForStream()
                .range(properties.streamKey(), Range.unbounded()).size());
    }

    @Test
    void retriesUseSameEventIdAndBackoffBeforeReturningToPending() {
        OutboxEvent event = newEvent("EVALUATION_SUBMITTED", 702L);
        ClaimedOutboxEvent claim = lifecycle.claimBatch().get(0);

        assertEquals(FailureDisposition.RETRY_SCHEDULED,
                lifecycle.recordFailure(claim,
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "Redis timeout")));
        OutboxEvent pending = eventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PENDING, pending.getProcessingStatus());
        assertEquals(1, pending.getRetryCount());
        assertNotNull(pending.getNextAttemptAt());
        assertNotNull(pending.getLastError());

        jdbc.update("update outbox_events set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), event.getId());
        publisher.publishBatch();

        OutboxEvent published = eventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getProcessingStatus());
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(properties.streamKey(), Range.unbounded());
        assertEquals(1, records.size());
        assertEquals(event.getId().toString(), records.get(0).getValue().get("eventId"));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleClaimCannotFinalizeIt() {
        OutboxEvent event = newEvent("EVALUATION_SUBMITTED", 703L);
        ClaimedOutboxEvent firstClaim = lifecycle.claimBatch().get(0);
        jdbc.update("update outbox_events set claimed_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(10)), event.getId());

        ClaimedOutboxEvent recoveredClaim = lifecycle.claimBatch().get(0);

        assertEquals(event.getId(), recoveredClaim.id());
        assertEquals(2, recoveredClaim.attemptNumber());
        assertNotEquals(firstClaim.claimToken(), recoveredClaim.claimToken());
        assertFalse(lifecycle.markPublished(firstClaim));
        assertTrue(lifecycle.markPublished(recoveredClaim));
        assertEquals(OutboxEventStatus.PUBLISHED,
                eventRepository.findById(event.getId()).orElseThrow().getProcessingStatus());
    }

    @Test
    void crashAfterRedisAcceptsEventCanRepublishSameStableEventIdentity() {
        OutboxEvent event = newEvent("EVALUATION_SUBMITTED", 707L);
        ClaimedOutboxEvent firstClaim = lifecycle.claimBatch().get(0);
        streamPublisher.publish(firstClaim);
        jdbc.update("update outbox_events set claimed_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(10)), event.getId());

        ClaimedOutboxEvent recoveredClaim = lifecycle.claimBatch().get(0);
        streamPublisher.publish(recoveredClaim);
        assertTrue(lifecycle.markPublished(recoveredClaim));

        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(properties.streamKey(), Range.unbounded());
        assertEquals(2, records.size());
        assertEquals(event.getId().toString(), records.get(0).getValue().get("eventId"));
        assertEquals(event.getId().toString(), records.get(1).getValue().get("eventId"));
        assertEquals(event.getIdempotencyKey(),
                records.get(0).getValue().get("idempotencyKey"));
        assertEquals(event.getIdempotencyKey(),
                records.get(1).getValue().get("idempotencyKey"));
    }

    @Test
    void concurrentClaimersReceiveDifferentEvents() throws Exception {
        OutboxEvent first = newEvent("EVALUATION_SUBMITTED", 704L);
        OutboxEvent second = newEvent("EVALUATION_SUBMITTED", 705L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedOutboxEvent>> left = executor.submit(() -> {
                ready.countDown();
                start.await();
                return lifecycle.claimBatch();
            });
            Future<List<ClaimedOutboxEvent>> right = executor.submit(() -> {
                ready.countDown();
                start.await();
                return lifecycle.claimBatch();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            ClaimedOutboxEvent leftClaim = left.get(20, TimeUnit.SECONDS).get(0);
            ClaimedOutboxEvent rightClaim = right.get(20, TimeUnit.SECONDS).get(0);
            assertNotEquals(leftClaim.id(), rightClaim.id());
            assertEquals(
                    java.util.Set.of(first.getId(), second.getId()),
                    java.util.Set.of(leftClaim.id(), rightClaim.id()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aggregateEventsAreClaimedInCreatedOrder() {
        Long organizationId = newOrganization();
        Instant firstCreated = Instant.now().minusSeconds(1);
        OutboxEvent first = newEvent(
                organizationId, "QueueConfiguration", "QUEUE_OPENED", 708L,
                "{\"queueConfigurationId\":708}", firstCreated);
        OutboxEvent second = newEvent(
                organizationId, "QueueConfiguration", "QUEUE_CLOSED", 708L,
                "{\"queueConfigurationId\":708}", firstCreated.plusMillis(1));

        ClaimedOutboxEvent firstClaim = lifecycle.claimBatch().get(0);
        assertEquals(first.getId(), firstClaim.id());
        assertTrue(lifecycle.claimBatch().isEmpty());
        assertTrue(lifecycle.markPublished(firstClaim));

        ClaimedOutboxEvent secondClaim = lifecycle.claimBatch().get(0);
        assertEquals(second.getId(), secondClaim.id());
    }

    @Test
    void exhaustedAttemptsBecomeFailed() {
        OutboxEvent event = newEvent("EVALUATION_SUBMITTED", 706L);
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            jdbc.update("update outbox_events set next_attempt_at = ? where id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), event.getId());
            ClaimedOutboxEvent claim = lifecycle.claimBatch().get(0);
            FailureDisposition disposition = lifecycle.recordFailure(
                    claim,
                    new org.springframework.dao.DataAccessResourceFailureException(
                            "Redis unavailable"));
            if (attempt < properties.maxAttempts()) {
                assertEquals(FailureDisposition.RETRY_SCHEDULED, disposition);
            } else {
                assertEquals(FailureDisposition.EXHAUSTED, disposition);
            }
        }
        OutboxEvent failed = eventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.FAILED, failed.getProcessingStatus());
        assertNull(failed.getClaimToken());
        assertEquals(properties.maxAttempts(), failed.getRetryCount());
    }

    private OutboxEvent newEvent(String eventType, Long aggregateId) {
        return newEvent(newOrganization(), eventType, aggregateId, Instant.now());
    }

    private OutboxEvent newEvent(
            Long organizationId, String eventType, Long aggregateId, Instant createdAt) {
        return newEvent(
                organizationId, "InterviewEvaluation", eventType, aggregateId,
                "{\"evaluationId\":" + aggregateId + "}", createdAt);
    }

    private OutboxEvent newEvent(
            Long organizationId, String aggregateType, String eventType,
            Long aggregateId, String payload, Instant createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setOrganizationId(organizationId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setIdempotencyKey("evaluation-submitted:" + organizationId + ":" + aggregateId);
        event.setCreatedAt(createdAt);
        return eventRepository.saveAndFlush(event);
    }

    private Long newOrganization() {
        return jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values (?, 'UNIVERSITY', 'ACTIVE', now(), now())
                returning id
                """, Long.class, "outbox-" + UUID.randomUUID());
    }
}

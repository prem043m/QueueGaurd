package com.queueguard.qms.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.queueguard.qms.domain.InboxEventStatus;
import com.queueguard.qms.repo.InboxEventRepository;
import com.queueguard.qms.service.ConsumerEvent;
import com.queueguard.qms.service.ConsumerEventDeserializer;
import com.queueguard.qms.service.InboxEventFailureService;
import com.queueguard.qms.service.InboxEventProcessingService;
import com.queueguard.qms.service.OutboxConsumerProperties;
import com.queueguard.qms.service.RedisDeadLetterPublisher;
import com.queueguard.qms.service.RedisStreamConsumer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
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
        "queueguard.outbox.enabled=false",
        "queueguard.consumer.enabled=true",
        "queueguard.consumer.stream-key=qg:test:qms:consumer-events",
        "queueguard.consumer.group-name=qg:test:qms:consumer-group",
        "queueguard.consumer.consumer-name=qms-consumer-test-v1",
        "queueguard.consumer.consumer-identity=test-instance",
        "queueguard.consumer.batch-size=1",
        "queueguard.consumer.blocking-timeout-ms=10",
        "queueguard.consumer.polling-interval-ms=3600000",
        "queueguard.consumer.pending-recovery-interval-ms=3600000",
        "queueguard.consumer.minimum-pending-idle-ms=1",
        "queueguard.consumer.max-attempts=3",
        "queueguard.consumer.initial-retry-delay-ms=1",
        "queueguard.consumer.max-retry-delay-ms=10",
        "queueguard.consumer.dead-letter-stream-key=qg:test:qms:consumer-dead-letter"
})
@Testcontainers(disabledWithoutDocker = true)
class OutboxConsumerPostgresRedisTest {
    private static final String STREAM = "qg:test:qms:consumer-events";
    private static final String GROUP = "qg:test:qms:consumer-group";
    private static final String DEAD_LETTER = "qg:test:qms:consumer-dead-letter";
    private static final AtomicLong EVENT_IDS =
            new AtomicLong(System.currentTimeMillis() * 1000);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_consumer_test")
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
    @Autowired private InboxEventRepository inboxRepository;
    @Autowired private InboxEventProcessingService processing;
    @Autowired private InboxEventFailureService failures;
    @Autowired private ConsumerEventDeserializer deserializer;
    @Autowired private OutboxConsumerProperties properties;
    @Autowired private RedisDeadLetterPublisher deadLetterPublisher;
    @Autowired private StringRedisTemplate redis;
    private RedisStreamConsumer consumer;

    @BeforeEach
    void clearState() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
        redis.delete(STREAM);
        redis.delete(DEAD_LETTER);
        Set<String> dedupeKeys = redis.keys(DEAD_LETTER + ":dedupe:*");
        if (dedupeKeys != null && !dedupeKeys.isEmpty()) {
            redis.delete(dedupeKeys);
        }
        consumer = new RedisStreamConsumer(
                redis, properties, deserializer, processing, failures, deadLetterPublisher);
    }

    @Test
    void createsConsumerGroupProcessesInboxAndAcknowledgesOnlyAfterCommit() {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");

        consumer.pollNewMessages();

        assertInbox(eventId, InboxEventStatus.PROCESSED, 1);
        assertEquals(0, pendingCount());
    }

    @Test
    void duplicateProducerDeliveryCreatesOneInboxRecordAndOneDatabaseEffect() {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");

        consumer.pollNewMessages();
        consumer.pollNewMessages();

        assertInbox(eventId, InboxEventStatus.PROCESSED, 1);
        assertEquals(0, pendingCount());
    }

    @Test
    void commitBeforeAckCrashIsRecoveredAndDeduplicated() throws Exception {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        MapRecord<String, Object, Object> abandoned = readAs("crashed-consumer");
        ConsumerEvent event = deserializer.deserialize(abandoned);

        processing.process(event, 1);

        assertInbox(eventId, InboxEventStatus.PROCESSED, 1);
        await(() -> pendingCount() == 0, consumer::recoverPendingMessages);
        assertInbox(eventId, InboxEventStatus.PROCESSED, 1);
    }

    @Test
    void failedDatabaseTransactionLeavesMessagePendingAndCanRecover() throws Exception {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        installProcessingFailureTrigger();
        try {
            consumer.pollNewMessages();
            assertEquals(1, pendingCount());
            assertEquals(1, inboxRepository.findAttemptCount(
                    properties.consumerName(), eventId).orElseThrow());
        } finally {
            removeProcessingFailureTrigger();
        }

        await(() -> inboxRepository.findAttemptCount(
                        properties.consumerName(), eventId).orElse(0) > 0,
                consumer::recoverPendingMessages);
        await(() -> inboxStatus(eventId) == InboxEventStatus.PROCESSED,
                consumer::recoverPendingMessages);
        assertEquals(0, pendingCount());
    }

    @Test
    void transientFailuresReachConfiguredLimitThenPublishDeadLetterAndAck() throws Exception {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        installProcessingFailureTrigger();
        try {
            consumer.pollNewMessages();
            await(() -> deadLetterRecords().size() == 1,
                    consumer::recoverPendingMessages);
        } finally {
            removeProcessingFailureTrigger();
        }

        assertInbox(eventId, InboxEventStatus.DEAD_LETTERED, properties.maxAttempts());
        assertEquals(0, pendingCount());
        Map<Object, Object> deadLetter = deadLetterRecords().get(0).getValue();
        assertEquals(eventId, deadLetter.get("eventId"));
        assertEquals("3", deadLetter.get("attemptCount"));
        assertNotNull(deadLetter.get("failureReason"));
        assertNotNull(deadLetter.get("failedAt"));
    }

    @Test
    void unknownEventIsDeadLetteredAndDeadLetterFailureDoesNotAckOriginal() throws Exception {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "UNRECOGNIZED_EVENT");
        redis.opsForValue().set(DEAD_LETTER, "wrong-type");

        consumer.pollNewMessages();

        assertEquals(1, pendingCount());
        redis.delete(DEAD_LETTER);
        await(() -> pendingCount() == 0, consumer::recoverPendingMessages);

        assertInbox(eventId, InboxEventStatus.DEAD_LETTERED, 2);
        assertEquals(1, deadLetterRecords().size());
        assertEquals(eventId, deadLetterRecords().get(0).getValue().get("eventId"));
    }

    @Test
    void concurrentConsumersReadDifferentMessagesFromOneGroup() throws Exception {
        long organizationId = createOrganization();
        String firstId = nextEventId();
        String secondId = nextEventId();
        addEvent(firstId, organizationId, "EVALUATION_SUBMITTED");
        addEvent(secondId, organizationId, "QUEUE_ENTRY_REGISTERED");
        RedisStreamConsumer secondConsumer = new RedisStreamConsumer(
                redis, properties, deserializer, processing, failures,
                deadLetterPublisher);
        // The second poller shares the logical group but gets a fresh Redis consumer identity.
        assertNotNull(secondConsumer);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstPoll = executor.submit(() -> {
                awaitLatch(start);
                consumer.pollNewMessages();
            });
            Future<?> secondPoll = executor.submit(() -> {
                awaitLatch(start);
                secondConsumer.pollNewMessages();
            });
            start.countDown();
            firstPoll.get(10, TimeUnit.SECONDS);
            secondPoll.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertInbox(firstId, InboxEventStatus.PROCESSED, 1);
        assertInbox(secondId, InboxEventStatus.PROCESSED, 1);
        assertEquals(0, pendingCount());
    }

    @Test
    void competingRecoverersClaimAbandonedMessageOnlyOnce() throws Exception {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        readAs("abandoned-consumer");
        RedisStreamConsumer secondConsumer = new RedisStreamConsumer(
                redis, properties, deserializer, processing, failures, deadLetterPublisher);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRecovery = executor.submit(() -> {
                awaitLatch(start);
                consumer.recoverPendingMessages();
            });
            Future<?> secondRecovery = executor.submit(() -> {
                awaitLatch(start);
                secondConsumer.recoverPendingMessages();
            });
            start.countDown();
            firstRecovery.get(10, TimeUnit.SECONDS);
            secondRecovery.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        await(() -> pendingCount() == 0, consumer::recoverPendingMessages);
        assertInbox(eventId, InboxEventStatus.PROCESSED, 2);
    }

    @Test
    void deletedPendingEntryIsDeadLetteredAndAcknowledged() {
        long organizationId = createOrganization();
        RecordId messageId = addEvent(
                nextEventId(), organizationId, "EVALUATION_SUBMITTED");
        readAs("deleted-entry-consumer");
        redis.opsForStream().delete(STREAM, messageId);

        consumer.recoverPendingMessages();

        assertEquals(0, pendingCount());
        assertEquals(1, deadLetterRecords().size());
        Map<Object, Object> deadLetter = deadLetterRecords().get(0).getValue();
        assertEquals(messageId.getValue(), deadLetter.get("sourceMessageId"));
        assertTrue(((String) deadLetter.get("failureReason"))
                .contains("original payload unavailable"));
    }

    @Test
    void consumerRecreatesGroupAfterRedisGroupDisappears() {
        long organizationId = createOrganization();
        String firstId = nextEventId();
        addEvent(firstId, organizationId, "EVALUATION_SUBMITTED");
        consumer.pollNewMessages();
        assertInbox(firstId, InboxEventStatus.PROCESSED, 1);

        redis.opsForStream().destroyGroup(STREAM, GROUP);
        String secondId = nextEventId();
        addEvent(secondId, organizationId, "QUEUE_ENTRY_REGISTERED");
        consumer.pollNewMessages();
        consumer.pollNewMessages();
        consumer.pollNewMessages();

        assertInbox(firstId, InboxEventStatus.PROCESSED, 1);
        assertInbox(secondId, InboxEventStatus.PROCESSED, 1);
        assertEquals(0, pendingCount());
    }

    @Test
    void failedAckAfterCommitIsHarmlessWhenGroupRestarts() {
        long organizationId = createOrganization();
        String eventId = nextEventId();
        addEvent(eventId, organizationId, "EVALUATION_SUBMITTED");
        MapRecord<String, Object, Object> abandoned = readAs("ack-unavailable");
        processing.process(deserializer.deserialize(abandoned), 1);

        redis.opsForStream().destroyGroup(STREAM, GROUP);
        new RedisStreamConsumer(redis, properties, deserializer, processing,
                failures, deadLetterPublisher).pollNewMessages();

        assertInbox(eventId, InboxEventStatus.PROCESSED, 1);
        assertEquals(0, pendingCount());
    }

    private MapRecord<String, Object, Object> readAs(String consumerName) {
        try {
            redis.opsForStream().createGroup(
                    STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (org.springframework.dao.DataAccessException exception) {
            if (exception.getMessage() == null
                    || !exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
        var records = redis.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertNotNull(records);
        assertEquals(1, records.size());
        return records.get(0);
    }

    private RecordId addEvent(String eventId, long organizationId, String eventType) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("eventType", eventType);
        fields.put("organizationId", Long.toString(organizationId));
        fields.put("aggregateType", "InterviewEvaluation");
        fields.put("aggregateId", eventId);
        fields.put("payload", "{\"organizationId\":" + organizationId
                + ",\"evaluationId\":" + eventId + "}");
        fields.put("createdAt", Instant.now().toString());
        fields.put("idempotencyKey", "test:" + organizationId + ":" + eventId);
        return redis.opsForStream().add(STREAM, fields);
    }

    private long createOrganization() {
        return jdbc.queryForObject("""
                INSERT INTO organizations
                    (name, organization_type, status, created_at, updated_at)
                VALUES (?, 'UNIVERSITY', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, "consumer-" + nextEventId());
    }

    private static String nextEventId() {
        return Long.toString(EVENT_IDS.incrementAndGet());
    }

    private int pendingCount() {
        PendingMessages pending = redis.opsForStream().pending(
                STREAM, GROUP, Range.unbounded(), 100);
        int count = 0;
        for (var ignored : pending) {
            count++;
        }
        return count;
    }

    private List<MapRecord<String, Object, Object>> deadLetterRecords() {
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(DEAD_LETTER, Range.unbounded());
        return records == null ? List.of() : records;
    }

    private InboxEventStatus inboxStatus(String eventId) {
        return jdbc.queryForObject("""
                SELECT processing_status FROM qms_event_inbox
                WHERE consumer_name = ? AND event_id = ?
                """, InboxEventStatus.class, properties.consumerName(), eventId);
    }

    private void assertInbox(String eventId, InboxEventStatus status, int attemptCount) {
        var inbox = inboxRepository.findForUpdate(properties.consumerName(), eventId)
                .orElseThrow();
        assertEquals(status, inbox.getProcessingStatus());
        assertEquals(attemptCount, inbox.getAttemptCount());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM qms_event_inbox
                WHERE consumer_name = ? AND event_id = ?
                """, Integer.class, properties.consumerName(), eventId));
    }

    private void installProcessingFailureTrigger() {
        jdbc.execute("""
                CREATE FUNCTION qms_test_fail_processing() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.processing_status = 'PROCESSING' THEN
                    RAISE EXCEPTION 'injected processing failure';
                  END IF;
                  RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER qms_test_fail_processing_trigger
                BEFORE UPDATE ON qms_event_inbox
                FOR EACH ROW EXECUTE FUNCTION qms_test_fail_processing()
                """);
    }

    private void removeProcessingFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS qms_test_fail_processing_trigger "
                + "ON qms_event_inbox");
        jdbc.execute("DROP FUNCTION IF EXISTS qms_test_fail_processing()");
    }

    private static void await(BooleanSupplier condition, Runnable action) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            action.run();
            if (System.nanoTime() >= deadline) {
                assertTrue(condition.getAsBoolean(), "condition not reached before timeout");
            }
            Thread.sleep(10);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("poll was interrupted", exception);
        }
    }
}

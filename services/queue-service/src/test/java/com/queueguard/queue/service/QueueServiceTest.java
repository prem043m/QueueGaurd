package com.queueguard.queue.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.queue.dto.QueueDtos.ClaimRequest;
import com.queueguard.queue.dto.QueueDtos.ClaimResponse;
import com.queueguard.queue.dto.QueueDtos.CreateQueueRequest;
import com.queueguard.queue.dto.QueueDtos.EnqueueRequest;
import com.queueguard.queue.dto.QueueDtos.EnqueueResponse;
import com.queueguard.queue.entity.Message;
import com.queueguard.queue.entity.MessageStatus;
import com.queueguard.queue.entity.QueueDef;
import com.queueguard.queue.queue.RedisReadyQueue;
import com.queueguard.queue.repo.MessageRepository;
import com.queueguard.queue.repo.QueueDefRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueueServiceTest {
    private QueueDefRepository queues;
    private MessageRepository messages;
    private RedisReadyQueue readyQueue;
    private QueueService service;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        queues = mock(QueueDefRepository.class);
        messages = mock(MessageRepository.class);
        readyQueue = mock(RedisReadyQueue.class);
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        service = new QueueService(queues, messages, readyQueue, 60_000, meterRegistry, clock);
    }

    @Test
    void createQueueRejectsExistingName() {
        when(queues.existsByName("interviews")).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createQueue(new CreateQueueRequest("interviews", "Interview queue", 4, 3)));

        assertEquals(409, error.getStatus());
        verify(queues, never()).save(any());
    }

    @Test
    void enqueueUsesStoredMessageWhenIdempotencyKeyAlreadyExists() throws Exception {
        QueueDef queue = queue("interviews");
        Message existing = new Message();
        set(existing, "id", "existing-message");
        set(existing, "queueName", "interviews");
        when(queues.findByName("interviews")).thenReturn(Optional.of(queue));
        when(messages.findByQueueNameAndIdempotencyKey("interviews", "registration-42"))
                .thenReturn(Optional.of(existing));

        EnqueueResponse response = service.enqueue("interviews",
                new EnqueueRequest("{\"student\":42}", 8, "registration-42", 0L));

        assertEquals("existing-message", response.messageId());
        assertTrue(response.duplicate());
        verify(messages, never()).save(any());
        verifyNoInteractions(readyQueue);
        assertEquals(1.0, meterRegistry.get("queueguard.queue.messages")
                .tag("result", "duplicate_enqueue").counter().count());
        assertEquals(1, meterRegistry.get("queueguard.queue.operation.duration")
                .tag("operation", "enqueue").timer().count());
    }

    @Test
    void enqueuePersistsAndPlacesNewMessageOnPriorityReadyQueue() throws Exception {
        QueueDef queue = queue("interviews");
        when(queues.findByName("interviews")).thenReturn(Optional.of(queue));
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            set(message, "id", "new-message");
            return message;
        }).when(messages).save(any(Message.class));

        EnqueueResponse response = service.enqueue("interviews",
                new EnqueueRequest("{\"student\":99}", 9, "registration-99", 0L));

        assertEquals("new-message", response.messageId());
        assertFalse(response.duplicate());
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertEquals("interviews", saved.getValue().getQueueName());
        assertEquals(9, saved.getValue().getPriority());
        verify(readyQueue).push(eq("interviews"), eq("new-message"), eq(9), any(Instant.class));
        assertEquals(1.0, meterRegistry.get("queueguard.queue.messages")
                .tag("result", "enqueued").counter().count());
        assertEquals(1, meterRegistry.get("queueguard.queue.operations")
                .tag("operation", "enqueue").tag("outcome", "success").counter().count());
    }

    @Test
    void claimExistingEmptyQueueReturnsSuccessfulEmptyBatch() {
        when(queues.findByName("interviews")).thenReturn(Optional.of(queue("interviews")));
        when(readyQueue.pop("interviews", 4)).thenReturn(List.of());

        ClaimResponse response = service.claim("interviews", new ClaimRequest(4, "worker-1"));

        assertEquals("interviews", response.queue());
        assertEquals("worker-1", response.workerId());
        assertEquals(0, response.claimed());
        assertTrue(response.messages().isEmpty());
    }

    @Test
    void claimReturnsReadyMessage() throws Exception {
        Message message = new Message();
        set(message, "id", "message-1");
        message.setQueueName("interviews");
        message.setPayload("{\"workMs\":10}");
        message.setClaimedBy("worker-1");
        message.setStatus(MessageStatus.CLAIMED);
        when(queues.findByName("interviews")).thenReturn(Optional.of(queue("interviews")));
        when(readyQueue.pop("interviews", 1)).thenReturn(List.of("message-1"));
        when(messages.claimOne(eq("message-1"), eq("worker-1"), any(Instant.class),
                eq(MessageStatus.PENDING), eq(MessageStatus.CLAIMED))).thenReturn(1);
        when(messages.findById("message-1")).thenReturn(Optional.of(message));

        ClaimResponse response = service.claim("interviews", new ClaimRequest(1, "worker-1"));

        assertEquals(1, response.claimed());
        assertEquals("message-1", response.messages().get(0).id());
        assertEquals("worker-1", response.messages().get(0).claimedBy());
    }

    @Test
    void claimMissingQueueThrowsNotFound() {
        when(queues.findByName("missing")).thenReturn(Optional.empty());

        NotFoundException error = assertThrows(NotFoundException.class,
                () -> service.claim("missing", new ClaimRequest(1, "worker-1")));

        assertEquals(404, error.getStatus());
        assertEquals("NOT_FOUND", error.getCode());
    }

    @Test
    void listingQueuesRefreshesAggregateWorkloadWithoutQueueNameLabels() throws Exception {
        stubSnapshot();

        assertTrue(Double.isNaN(snapshotAge()));

        service.listQueues();

        assertEquals(3.0, meterRegistry.get("queueguard.queue.pending").gauge().value());
        assertEquals(2.0, meterRegistry.get("queueguard.queue.claimed").gauge().value());
        assertEquals(0.0, snapshotAge());
        clock.advanceMillis(3_000);
        assertEquals(3.0, snapshotAge());

        service.listQueues();
        assertEquals(0.0, snapshotAge());
        assertTrue(meterRegistry.get("queueguard.queue.pending").gauge().getId()
                .getTags().isEmpty());
    }

    @Test
    void failedSnapshotRefreshDoesNotAdvanceTheTimestamp() {
        stubSnapshot();
        service.listQueues();
        clock.advanceMillis(4_000);
        when(queues.findAll()).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, service::listQueues);

        assertEquals(4.0, snapshotAge());
    }

    private void stubSnapshot() {
        QueueDef queue = queue("interviews");
        when(queues.findAll()).thenReturn(List.of(queue));
        when(messages.countGroupedByStatus("interviews")).thenReturn(Arrays.asList(
                new Object[] {MessageStatus.PENDING, 3L},
                new Object[] {MessageStatus.CLAIMED, 2L}));
        when(readyQueue.depth("interviews")).thenReturn(3L);
    }

    private double snapshotAge() {
        return meterRegistry.get("queueguard.queue.state.snapshot.age.seconds")
                .gauge().value();
    }

    private static QueueDef queue(String name) {
        QueueDef queue = new QueueDef();
        queue.setName(name);
        queue.setMaxConcurrency(4);
        queue.setMaxAttempts(3);
        return queue;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong currentMillis;

        private MutableClock(long currentMillis) {
            this.currentMillis = new AtomicLong(currentMillis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis.get());
        }

        @Override
        public long millis() {
            return currentMillis.get();
        }

        private void advanceMillis(long amount) {
            currentMillis.addAndGet(amount);
        }
    }
}

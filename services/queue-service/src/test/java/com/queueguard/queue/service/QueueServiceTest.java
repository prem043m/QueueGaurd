package com.queueguard.queue.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.queue.dto.QueueDtos.CreateQueueRequest;
import com.queueguard.queue.dto.QueueDtos.EnqueueRequest;
import com.queueguard.queue.dto.QueueDtos.EnqueueResponse;
import com.queueguard.queue.entity.Message;
import com.queueguard.queue.entity.QueueDef;
import com.queueguard.queue.queue.RedisReadyQueue;
import com.queueguard.queue.repo.MessageRepository;
import com.queueguard.queue.repo.QueueDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueueServiceTest {
    private QueueDefRepository queues;
    private MessageRepository messages;
    private RedisReadyQueue readyQueue;
    private QueueService service;

    @BeforeEach
    void setUp() {
        queues = mock(QueueDefRepository.class);
        messages = mock(MessageRepository.class);
        readyQueue = mock(RedisReadyQueue.class);
        service = new QueueService(queues, messages, readyQueue, 60_000);
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
}

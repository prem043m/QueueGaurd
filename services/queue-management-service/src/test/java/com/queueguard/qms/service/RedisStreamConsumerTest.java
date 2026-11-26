package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamConsumerTest {
    @Test
    @SuppressWarnings("unchecked")
    void unexpectedProcessingExceptionIsRecordedOnSpanAndPropagated() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(stream);
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        Map<Object, Object> fields = Map.of("eventId", "7");
        when(record.getValue()).thenReturn(fields);
        when(stream.read(
                any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        OutboxConsumerProperties properties = new OutboxConsumerProperties(
                true, "events", "group", "consumer", "test", 1,
                1, 1, 1, 1, 3, 1, 10, "dead-letter");
        ConsumerEventDeserializer deserializer = mock(ConsumerEventDeserializer.class);
        IllegalStateException failure = new IllegalStateException("unexpected");
        when(deserializer.deserialize(record)).thenThrow(failure);
        InboxEventProcessingService processing = mock(InboxEventProcessingService.class);
        InboxEventFailureService failures = mock(InboxEventFailureService.class);
        RedisDeadLetterPublisher deadLetterPublisher = mock(RedisDeadLetterPublisher.class);
        QmsEventMetrics metrics = new QmsEventMetrics(new SimpleMeterRegistry());
        Tracer tracer = mock(Tracer.class);
        OutboxTraceContext outboxTraceContext = mock(OutboxTraceContext.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(outboxTraceContext.consumerSpanBuilder(fields)).thenReturn(builder);
        when(builder.name("outbox.consume")).thenReturn(builder);
        when(builder.kind(Span.Kind.CONSUMER)).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);

        RedisStreamConsumer consumer = new RedisStreamConsumer(
                redis, properties, deserializer, processing, failures,
                deadLetterPublisher, metrics, tracer, outboxTraceContext);

        assertSame(failure, assertThrows(
                IllegalStateException.class, consumer::pollNewMessages));
        verify(span).error(failure);
        verify(span).end();
        verify(scope).close();
        verify(stream, never()).acknowledge(
                anyString(), anyString(), any(RecordId.class));
    }
}

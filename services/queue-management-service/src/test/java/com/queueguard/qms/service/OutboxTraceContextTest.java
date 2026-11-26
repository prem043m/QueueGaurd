package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queueguard.qms.domain.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxTraceContextTest {
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private Tracer tracer;
    private Propagator propagator;
    private OutboxTraceContext traceContext;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        propagator = mock(Propagator.class);
        traceContext = new OutboxTraceContext(tracer, propagator);
    }

    @Test
    void capturesW3cContextIntoOutboxColumns() {
        Span span = mock(Span.class);
        TraceContext current = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(current);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            carrier.put("traceparent", TRACEPARENT);
            carrier.put("tracestate", "vendor=value");
            return null;
        }).when(propagator).inject(eq(current), any(), any());

        OutboxEvent event = new OutboxEvent();
        traceContext.capture(event);

        assertEquals(TRACEPARENT, event.getTraceparent());
        assertEquals("vendor=value", event.getTracestate());
    }

    @Test
    void captureFailureLeavesTraceColumnsNullWithoutFailingTheInsert() {
        Span span = mock(Span.class);
        TraceContext current = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(current);
        doThrow(new IllegalStateException("propagator unavailable"))
                .when(propagator).inject(eq(current), any(), any());

        OutboxEvent event = new OutboxEvent();
        event.setTraceparent(TRACEPARENT);
        event.setTracestate("old=value");

        assertDoesNotThrow(() -> traceContext.capture(event));
        assertNull(event.getTraceparent());
        assertNull(event.getTracestate());
    }

    @Test
    void extractsValidContextAndCapsTracestate() {
        Span.Builder builder = mock(Span.Builder.class);
        String tracestate = "x".repeat(600);
        when(propagator.extract(any(), any())).thenAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(0);
            assertEquals(TRACEPARENT, carrier.get("traceparent"));
            assertEquals(512, carrier.get("tracestate").length());
            return builder;
        });

        Span.Builder extracted = traceContext.consumerSpanBuilder(
                Map.of("traceparent", TRACEPARENT, "tracestate", tracestate));

        assertSame(builder, extracted);
    }

    @Test
    void missingOrInvalidTraceparentStartsAParentlessSpan() {
        Span.Builder builder = mock(Span.Builder.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.setNoParent()).thenReturn(builder);

        assertSame(builder, traceContext.consumerSpanBuilder(Map.of()));
        assertSame(builder, traceContext.consumerSpanBuilder(
                Map.of("traceparent", "not-a-traceparent")));

        verify(propagator, never()).extract(any(), any());
        verify(tracer, org.mockito.Mockito.times(2)).spanBuilder();
    }
}

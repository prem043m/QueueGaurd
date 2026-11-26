package com.queueguard.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TraceContextHttpClientTest {
    @Test
    void injectsTheCurrentTraceContextIntoOutgoingW3cHeaders() throws Exception {
        AtomicReference<String> traceparent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            Tracer tracer = mock(Tracer.class);
            Span span = mock(Span.class);
            TraceContext context = mock(TraceContext.class);
            Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
            Propagator propagator = mock(Propagator.class);
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name(anyString())).thenReturn(span);
            when(span.tag(anyString(), anyString())).thenReturn(span);
            when(span.start()).thenReturn(span);
            when(span.context()).thenReturn(context);
            when(tracer.withSpan(span)).thenReturn(scope);
            when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
            when(context.spanId()).thenReturn("00f067aa0ba902b7");
            doAnswer(invocation -> {
                TraceContext traceContext = invocation.getArgument(0);
                HttpRequest.Builder request = invocation.getArgument(1);
                Propagator.Setter<HttpRequest.Builder> setter = invocation.getArgument(2);
                setter.set(request, "traceparent",
                        "00-" + traceContext.traceId() + "-" + traceContext.spanId() + "-01");
                return null;
            }).when(propagator).inject(any(TraceContext.class), any(), any());

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .GET();
            TraceContextHttpClient.send(
                    HttpClient.newHttpClient(), request, HttpResponse.BodyHandlers.discarding(),
                    tracer, propagator, "test-service");

            assertEquals(
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    traceparent.get());
        } finally {
            server.stop(0);
        }
    }
}

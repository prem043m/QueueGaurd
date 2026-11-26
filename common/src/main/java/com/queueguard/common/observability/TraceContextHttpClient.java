package com.queueguard.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class TraceContextHttpClient {
    private TraceContextHttpClient() {
    }

    public static <T> HttpResponse<T> send(
            HttpClient client,
            HttpRequest.Builder request,
            HttpResponse.BodyHandler<T> bodyHandler,
            Tracer tracer,
            Propagator propagator,
            String peerService) throws IOException, InterruptedException {
        try (ScopedSpan span = startSpan(tracer, "http.client", peerService)) {
            try {
                span.inject(propagator, request);
                HttpResponse<T> response = client.send(request.build(), bodyHandler);
                span.tag("http.status_code", Integer.toString(response.statusCode()));
                return response;
            } catch (IOException | InterruptedException | RuntimeException exception) {
                span.error(exception);
                throw exception;
            }
        }
    }

    public static ScopedSpan startSpan(Tracer tracer, String name, String peerService) {
        Span span = tracer.nextSpan()
                .name(name)
                .tag("peer.service", peerService)
                .start();
        return new ScopedSpan(tracer, span);
    }

    public static ScopedSpan startSpan(Tracer tracer, String name) {
        return new ScopedSpan(tracer, tracer.nextSpan().name(name).start());
    }

    public static final class ScopedSpan implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;

        private ScopedSpan(Tracer tracer, Span span) {
            this.span = span;
            this.scope = tracer.withSpan(span);
        }

        public void inject(Propagator propagator, HttpRequest.Builder request) {
            propagator.inject(span.context(), request,
                    (builder, key, value) -> builder.setHeader(key, value));
        }

        public void tag(String key, String value) {
            span.tag(key, value);
        }

        public void error(Throwable exception) {
            span.error(exception);
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}

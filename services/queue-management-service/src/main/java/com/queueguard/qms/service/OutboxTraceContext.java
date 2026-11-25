package com.queueguard.qms.service;

import com.queueguard.qms.domain.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxTraceContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxTraceContext.class);
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final int MAX_TRACESTATE_LENGTH = 512;

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void capture(OutboxEvent event) {
        event.setTraceparent(null);
        event.setTracestate(null);
        try {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan == null) {
                return;
            }
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(currentSpan.context(), carrier, Map::put);
            String traceparent = carrier.get("traceparent");
            if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
                return;
            }
            event.setTraceparent(traceparent);
            String tracestate = carrier.get("tracestate");
            if (tracestate != null && tracestate.length() <= MAX_TRACESTATE_LENGTH) {
                event.setTracestate(tracestate);
            }
        } catch (RuntimeException exception) {
            event.setTraceparent(null);
            event.setTracestate(null);
            LOGGER.warn("Could not capture outbox trace context ({})",
                    exception.getClass().getSimpleName());
        }
    }

    public Span.Builder consumerSpanBuilder(Map<?, ?> fields) {
        Object value = fields.get("traceparent");
        if (!(value instanceof String traceparent)
                || !TRACEPARENT.matcher(traceparent).matches()) {
            return tracer.spanBuilder().setNoParent();
        }

        Map<String, String> carrier = new HashMap<>();
        carrier.put("traceparent", traceparent);
        Object stateValue = fields.get("tracestate");
        if (stateValue instanceof String tracestate && !tracestate.isEmpty()) {
            carrier.put("tracestate",
                    tracestate.substring(0, Math.min(tracestate.length(), MAX_TRACESTATE_LENGTH)));
        }
        try {
            return propagator.extract(carrier, Map::get);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not extract outbox trace context ({})",
                    exception.getClass().getSimpleName());
            return tracer.spanBuilder().setNoParent();
        }
    }
}

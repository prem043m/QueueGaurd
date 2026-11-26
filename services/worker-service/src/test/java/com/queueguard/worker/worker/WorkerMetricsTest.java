package com.queueguard.worker.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.queueguard.worker.worker.WorkerMetrics.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerMetricsTest {
    @Test
    void recordsBoundedOutcomesDurationAndInFlightGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerMetrics metrics = new WorkerMetrics(registry);
        AtomicLong inFlight = new AtomicLong(2);
        metrics.bindInFlight(inFlight);

        metrics.recordJob(Outcome.SUCCESS, 1000);
        metrics.recordJob(Outcome.FAILURE, 2000);

        assertEquals(1.0, registry.get("queueguard.worker.jobs")
                .tag("outcome", "success").counter().count());
        assertEquals(1.0, registry.get("queueguard.worker.jobs")
                .tag("outcome", "failure").counter().count());
        assertEquals(2, registry.get("queueguard.worker.job.processing.duration")
                .timer().count());
        assertEquals(2.0, registry.get("queueguard.worker.jobs.in.flight")
                .gauge().value());
        inFlight.decrementAndGet();
        assertEquals(1.0, registry.get("queueguard.worker.jobs.in.flight")
                .gauge().value());
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertEquals("outcome", tag.getKey())));
    }
}

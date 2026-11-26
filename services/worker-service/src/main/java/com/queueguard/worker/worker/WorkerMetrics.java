package com.queueguard.worker.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class WorkerMetrics {
    private final MeterRegistry registry;

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void bindInFlight(AtomicLong inFlight) {
        Gauge.builder("queueguard.worker.jobs.in.flight", inFlight, AtomicLong::get)
                .register(registry);
    }

    public void recordJob(Outcome outcome, long durationNanos) {
        registry.counter("queueguard.worker.jobs", "outcome", outcome.label).increment();
        registry.timer("queueguard.worker.job.processing.duration")
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public enum Outcome {
        SUCCESS("success"),
        FAILURE("failure");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }
}

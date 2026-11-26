package com.queueguard.worker.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.observability.TraceContextHttpClient;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Periodically reports worker metrics to the monitor-service (best-effort). */
@Component
public class MonitorReporter {

    private static final Logger log = LoggerFactory.getLogger(MonitorReporter.class);

    private final WorkerEngine engine;
    private final String monitorUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Tracer tracer;
    private final Propagator propagator;

    public MonitorReporter(WorkerEngine engine,
                           @Value("${queueguard.monitor-url:http://localhost:8085}") String monitorUrl,
                           Tracer tracer,
                           Propagator propagator) {
        this.engine = engine;
        this.monitorUrl = monitorUrl.endsWith("/") ? monitorUrl.substring(0, monitorUrl.length() - 1) : monitorUrl;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Scheduled(fixedDelayString = "${queueguard.report-interval-ms:10000}")
    public void report() {
        try {
            Map<String, Object> stats = new HashMap<>(engine.stats());
            stats.put("service", "worker-service");
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(monitorUrl + "/monitor/metrics"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(stats)));
            TraceContextHttpClient.send(
                    http, request, HttpResponse.BodyHandlers.discarding(),
                    tracer, propagator, "monitor-service");
        } catch (Exception e) {
            log.debug("Monitor report failed: {}", e.getMessage());
        }
    }
}

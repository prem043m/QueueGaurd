package com.queueguard.worker.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public MonitorReporter(WorkerEngine engine,
                           @Value("${queueguard.monitor-url:http://localhost:8085}") String monitorUrl) {
        this.engine = engine;
        this.monitorUrl = monitorUrl.endsWith("/") ? monitorUrl.substring(0, monitorUrl.length() - 1) : monitorUrl;
    }

    @Scheduled(fixedDelayString = "${queueguard.report-interval-ms:10000}")
    public void report() {
        try {
            Map<String, Object> stats = new HashMap<>(engine.stats());
            stats.put("service", "worker-service");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(monitorUrl + "/monitor/metrics"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(stats)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Monitor report failed: {}", e.getMessage());
        }
    }
}

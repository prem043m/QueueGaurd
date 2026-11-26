package com.queueguard.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.monitor.entity.MonitorEntities.HealthCheck;
import com.queueguard.monitor.entity.MonitorEntities.LoadSnapshot;
import com.queueguard.monitor.entity.MonitorEntities.MetricSample;
import com.queueguard.monitor.repo.HealthCheckRepository;
import com.queueguard.monitor.repo.LoadSnapshotRepository;
import com.queueguard.monitor.repo.MetricSampleRepository;
import com.queueguard.common.observability.TraceContextHttpClient;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Observability + adaptive-control brain.
 *
 * Every few seconds it probes every service, computes a normalized load score
 * in [0,1] and pushes it to the rate-limit-service, which adjusts how much
 * traffic each IP is allowed to send. This closes the feedback loop:
 *
 *   attack/load -> higher load score -> smaller limits -> system protected
 *   idle system -> low load score   -> relaxed limits -> happy users
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final HealthCheckRepository healthRepo;
    private final MetricSampleRepository metricRepo;
    private final LoadSnapshotRepository loadRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** service name -> base url */
    private final Map<String, String> targets = new LinkedHashMap<>();
    /** service name -> recent probe latencies (rolling window for p95) */
    private final Map<String, Deque<Long>> latencies = new ConcurrentHashMap<>();

    private final String rateLimitUrl;
    private final String queueUrl;
    private final Tracer tracer;
    private final Propagator propagator;

    public MonitorService(HealthCheckRepository healthRepo,
                          MetricSampleRepository metricRepo,
                          LoadSnapshotRepository loadRepo,
                          @Value("${queueguard.targets}") String targetsCsv,
                          @Value("${queueguard.rate-limit-url:http://localhost:8084}") String rateLimitUrl,
                          @Value("${queueguard.queue-url:http://localhost:8082}") String queueUrl,
                          Tracer tracer,
                          Propagator propagator) {
        this.healthRepo = healthRepo;
        this.metricRepo = metricRepo;
        this.loadRepo = loadRepo;
        this.rateLimitUrl = trimSlash(rateLimitUrl);
        this.queueUrl = trimSlash(queueUrl);
        this.tracer = tracer;
        this.propagator = propagator;
        for (String pair : targetsCsv.split(",")) {
            // format: name=base-url, e.g. api-gateway=http://api-gateway:8080
            String[] nameAndUrl = pair.trim().split("=", 2);
            if (nameAndUrl.length == 2 && !nameAndUrl[0].isBlank() && !nameAndUrl[1].isBlank()) {
                targets.put(nameAndUrl[0].trim(), trimSlash(nameAndUrl[1].trim()));
            }
        }
    }

    // ------------------------------------------------------------------ probing

    @Scheduled(fixedDelayString = "${queueguard.probe-interval-ms:10000}")
    @Transactional
    public void probeAllAndComputeLoad() {
        long worstP95 = 0;
        for (Map.Entry<String, String> entry : targets.entrySet()) {
            worstP95 = Math.max(worstP95, probe(entry.getKey(), entry.getValue()));
        }

        long queueDepth = fetchQueueDepth();
        MetricSample worker = metricRepo.findTopByServiceOrderByReceivedAtDesc("worker-service").orElse(null);
        long inFlight = worker == null || worker.getInFlight() == null ? 0 : worker.getInFlight();
        long processed = worker == null || worker.getProcessed() == null ? 0 : worker.getProcessed();
        long failed = worker == null || worker.getFailed() == null ? 0 : worker.getFailed();
        double errorRate = (processed + failed) == 0 ? 0.0 : Math.min(1.0, failed / (double) (processed + failed));

        double qNorm = clamp(queueDepth / 200.0);
        double fNorm = clamp(inFlight / 32.0);
        double eNorm = clamp(errorRate);
        double lNorm = clamp(worstP95 / 1000.0);
        double loadScore = clamp(0.35 * qNorm + 0.25 * fNorm + 0.25 * eNorm + 0.15 * lNorm);

        LoadSnapshot snap = new LoadSnapshot();
        snap.setLoadScore(loadScore);
        snap.setQueueDepth(queueDepth);
        snap.setInFlight(inFlight);
        snap.setErrorRate(errorRate);
        snap.setP95LatencyMs(worstP95);
        loadRepo.save(snap);

        pushLoadToRateLimiter(loadScore, queueDepth, inFlight, errorRate, worstP95);
    }

    private long probe(String name, String baseUrl) {
        long start = System.currentTimeMillis();
        String status = "DOWN";
        long latency;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET();
            HttpResponse<String> res = TraceContextHttpClient.send(
                    http, request, HttpResponse.BodyHandlers.ofString(),
                    tracer, propagator, name);
            latency = System.currentTimeMillis() - start;
            status = res.statusCode() == 200 ? "UP" : "DOWN(" + res.statusCode() + ")";
        } catch (Exception e) {
            latency = System.currentTimeMillis() - start;
            status = "DOWN";
        }
        HealthCheck hc = new HealthCheck();
        hc.setService(name);
        hc.setStatus(status);
        hc.setLatencyMs(latency);
        healthRepo.save(hc);

        Deque<Long> window = latencies.computeIfAbsent(name, k -> new ConcurrentLinkedDeque<>());
        window.addLast(latency);
        while (window.size() > 50) {
            window.removeFirst();
        }
        return p95(window);
    }

    private void pushLoadToRateLimiter(double loadScore, long queueDepth, long inFlight, double errorRate, long p95) {
        try {
            String body = String.format(Locale.ROOT,
                    "{\"loadScore\":%.4f,\"queueDepth\":%d,\"inFlight\":%d,\"errorRate\":%.4f,\"p95LatencyMs\":%d}",
                    loadScore, queueDepth, inFlight, errorRate, p95);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(rateLimitUrl + "/ratelimit/load"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            TraceContextHttpClient.send(
                    http, request, HttpResponse.BodyHandlers.discarding(),
                    tracer, propagator, "rate-limit-service");
        } catch (Exception e) {
            log.debug("Could not push load to rate-limit-service: {}", e.getMessage());
        }
    }

    private long fetchQueueDepth() {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(queueUrl + "/queues"))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            HttpResponse<String> res = TraceContextHttpClient.send(
                    http, request, HttpResponse.BodyHandlers.ofString(),
                    tracer, propagator, "queue-service");
            if (res.statusCode() != 200) {
                return 0;
            }
            JsonNode data = mapper.readTree(res.body()).path("data");
            long total = 0;
            if (data.isArray()) {
                for (JsonNode q : data) {
                    total += q.path("pending").asLong(0) + q.path("claimed").asLong(0);
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ data access

    @Transactional
    public MetricSample recordMetrics(MetricSample sample) {
        return metricRepo.save(sample);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> healthOverview() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> services = new ArrayList<>();
        for (String name : targets.keySet()) {
            HealthCheck latest = healthRepo.findTopByServiceOrderByCheckedAtDesc(name).orElse(null);
            Map<String, Object> svc = new LinkedHashMap<>();
            svc.put("service", name);
            if (latest != null) {
                svc.put("status", latest.getStatus());
                svc.put("latencyMs", latest.getLatencyMs());
                svc.put("checkedAt", latest.getCheckedAt().toString());
            } else {
                svc.put("status", "UNKNOWN");
            }
            services.add(svc);
        }
        out.put("services", services);
        out.put("load", loadRepo.findTopByOrderByCreatedAtDesc().orElse(null));
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<LoadSnapshot> latestLoad() {
        return loadRepo.findTopByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<LoadSnapshot> timeline(int limit) {
        return loadRepo.findTop50ByOrderByCreatedAtDesc().stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<MetricSample> recentMetrics() {
        return metricRepo.findTop50ByOrderByReceivedAtDesc();
    }

    /** Housekeeping: keep at most 24h of telemetry. */
    @Scheduled(fixedDelayString = "${queueguard.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(24 * 3600);
        int a = healthRepo.deleteOlderThan(cutoff);
        int b = metricRepo.deleteOlderThan(cutoff);
        int c = loadRepo.deleteOlderThan(cutoff);
        if (a + b + c > 0) {
            log.info("Telemetry cleanup removed {} rows", a + b + c);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static long p95(Deque<Long> window) {
        if (window.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(window);
        Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

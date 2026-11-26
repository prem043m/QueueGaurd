package com.queueguard.worker.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.observability.TraceContextHttpClient;
import com.queueguard.worker.dto.WorkerDtos.*;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin REST client towards the queue-service (claim / ack / nack),
 * built on the JDK HttpClient to keep the dependency surface minimal.
 */
@Component
public class QueueClient {

    private static final Logger log = LoggerFactory.getLogger(QueueClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String baseUrl;
    private final String workerId;
    private final Tracer tracer;
    private final Propagator propagator;

    public QueueClient(@Value("${queueguard.queue-url:http://localhost:8082}") String baseUrl,
                       @Value("${queueguard.worker-id:worker-1}") String workerId,
                       Tracer tracer,
                       Propagator propagator) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.workerId = workerId;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public String workerId() {
        return workerId;
    }

    /** Claims up to {@code max} ready messages from the given queue. */
    public List<Task> claim(String queue, int max) {
        try {
            Envelope<ClaimData> env = post("/queues/" + queue + "/claim",
                    new ClaimWire(max, workerId), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (env == null || env.data() == null || env.data().messages() == null) {
                return List.of();
            }
            return env.data().messages().stream()
                    .map(m -> new Task(m.id(), m.queue(), m.payload(), m.priority(), m.attempts()))
                    .toList();
        } catch (QueueClientHttpException e) {
            if (e.statusCode() == 404) {
                throw new QueueNotFoundException(queue);
            }
            log.debug("claim({}) failed with HTTP {}: {}", queue, e.statusCode(), e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.debug("claim({}) failed: {}", queue, e.getMessage());
            return List.of();
        }
    }

    public boolean ack(String messageId) {
        try {
            post("/messages/" + messageId + "/ack", new WorkerActionWire(workerId, null),
                    new com.fasterxml.jackson.core.type.TypeReference<Envelope<Object>>() {});
            return true;
        } catch (Exception e) {
            log.warn("ack({}) failed: {}", messageId, e.getMessage());
            return false;
        }
    }

    public boolean nack(String messageId, String error) {
        try {
            post("/messages/" + messageId + "/nack", new WorkerActionWire(workerId, error),
                    new com.fasterxml.jackson.core.type.TypeReference<Envelope<Object>>() {});
            return true;
        } catch (Exception e) {
            log.warn("nack({}) failed: {}", messageId, e.getMessage());
            return false;
        }
    }

    private <T> T post(String path, Object body, com.fasterxml.jackson.core.type.TypeReference<T> type) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> res = TraceContextHttpClient.send(
                http, request, HttpResponse.BodyHandlers.ofString(),
                tracer, propagator, "queue-service");
        if (res.statusCode() >= 300) {
            throw new QueueClientHttpException(res.statusCode(), path);
        }
        return mapper.readValue(res.body(), type);
    }

    private static final class QueueClientHttpException extends RuntimeException {
        private final int statusCode;

        private QueueClientHttpException(int statusCode, String path) {
            super("HTTP " + statusCode + " from queue-service" + path);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}

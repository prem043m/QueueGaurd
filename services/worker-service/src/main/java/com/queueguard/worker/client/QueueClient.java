package com.queueguard.worker.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.worker.dto.WorkerDtos.*;
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

    public QueueClient(@Value("${queueguard.queue-url:http://localhost:8082}") String baseUrl,
                       @Value("${queueguard.worker-id:worker-1}") String workerId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.workerId = workerId;
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
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " from queue-service" + path);
        }
        return mapper.readValue(res.body(), type);
    }
}

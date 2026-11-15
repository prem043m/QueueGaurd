package com.queueguard.worker.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.worker.client.QueueClient;
import com.queueguard.worker.dto.WorkerDtos.Task;
import com.queueguard.worker.entity.JobResult;
import com.queueguard.worker.repo.JobResultRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency-controlled job consumer.
 *
 * A {@link Semaphore} caps the number of in-flight jobs; the poller never claims
 * more work than free permits, so a burst of messages can never over-parallelize
 * this pod. Crash-safety: if this process dies, the queue-service's visibility
 * timeout reclaims and re-delivers the in-flight messages.
 */
@Component
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueueClient queueClient;
    private final JobResultRepository results;
    private final List<String> queues;
    private final int batchSize;
    private final long defaultWorkMs;

    private final Semaphore semaphore;
    private final ExecutorService executor;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong processedOk = new AtomicLong();
    private final AtomicLong processedFail = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();

    public WorkerEngine(QueueClient queueClient,
                        JobResultRepository results,
                        @Value("${queueguard.queues:default}") String queuesCsv,
                        @Value("${queueguard.max-concurrency:8}") int maxConcurrency,
                        @Value("${queueguard.batch-size:4}") int batchSize,
                        @Value("${queueguard.default-work-ms:100}") long defaultWorkMs) {
        this.queueClient = queueClient;
        this.results = results;
        this.queues = List.of(queuesCsv.split(","));
        this.batchSize = batchSize;
        this.defaultWorkMs = defaultWorkMs;
        this.semaphore = new Semaphore(maxConcurrency);
        this.executor = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread t = new Thread(r, "worker-pool");
            t.setDaemon(true);
            return t;
        });
    }

    /** Poll loop: claim at most as many messages as we have free concurrency slots. */
    @Scheduled(fixedDelayString = "${queueguard.poll-interval-ms:500}")
    public void poll() {
        if (paused.get()) {
            return;
        }
        int free = semaphore.availablePermits();
        if (free == 0) {
            return;
        }
        int want = Math.min(batchSize, free);
        for (String queue : queues) {
            if (want <= 0) {
                break;
            }
            String q = queue.trim();
            if (q.isEmpty()) {
                continue;
            }
            List<Task> tasks = queueClient.claim(q, want);
            if (tasks.isEmpty()) {
                continue;
            }
            try {
                semaphore.acquire(tasks.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            want -= tasks.size();
            for (Task task : tasks) {
                executor.submit(() -> process(task));
            }
        }
    }

    private void process(Task task) {
        inFlight.incrementAndGet();
        long start = System.currentTimeMillis();
        try {
            long workMs = workMsOf(task.payload());
            Thread.sleep(workMs);
            if (task.payload() != null && task.payload().contains("\"fail\":true")) {
                throw new IllegalStateException("Simulated processing failure (payload requested fail:true)");
            }
            if (queueClient.ack(task.id())) {
                processedOk.incrementAndGet();
                saveResult(task, "OK", System.currentTimeMillis() - start, null);
            } else {
                processedFail.incrementAndGet();
                saveResult(task, "ACK_FAILED", System.currentTimeMillis() - start, "queue-service rejected ack");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queueClient.nack(task.id(), "interrupted");
            processedFail.incrementAndGet();
            saveResult(task, "INTERRUPTED", System.currentTimeMillis() - start, e.getMessage());
        } catch (Exception e) {
            queueClient.nack(task.id(), e.getMessage());
            processedFail.incrementAndGet();
            saveResult(task, "FAILED", System.currentTimeMillis() - start, e.getMessage());
            log.warn("Job {} failed: {}", task.id(), e.getMessage());
        } finally {
            inFlight.decrementAndGet();
            semaphore.release();
        }
    }

    private void saveResult(Task task, String status, long durationMs, String error) {
        try {
            JobResult r = new JobResult();
            r.setMessageId(task.id());
            r.setQueueName(task.queue());
            r.setWorkerId(queueClient.workerId());
            r.setStatus(status);
            r.setDurationMs(durationMs);
            r.setError(error);
            results.save(r);
        } catch (Exception e) {
            log.debug("Could not persist job result: {}", e.getMessage());
        }
    }

    /** Payload may carry {"workMs": N} to control simulated processing time. */
    private long workMsOf(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            if (node.has("workMs")) {
                return Math.max(0, Math.min(30_000, node.get("workMs").asLong(defaultWorkMs)));
            }
        } catch (Exception ignored) {
            // payload is not JSON; use the default
        }
        return defaultWorkMs;
    }

    // ------------------------------------------------------------------ control & stats

    public void pause() {
        paused.set(true);
        log.warn("Worker paused by operator");
    }

    public void resume() {
        paused.set(false);
        log.warn("Worker resumed by operator");
    }

    public Map<String, Object> stats() {
        return Map.of(
                "workerId", queueClient.workerId(),
                "paused", paused.get(),
                "inFlight", inFlight.get(),
                "availablePermits", semaphore.availablePermits(),
                "processedOk", processedOk.get(),
                "processedFail", processedFail.get(),
                "uptimeMs", System.currentTimeMillis() - startedAt
        );
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

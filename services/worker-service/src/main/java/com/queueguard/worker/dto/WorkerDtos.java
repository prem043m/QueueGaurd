package com.queueguard.worker.dto;

import java.util.List;

public final class WorkerDtos {

    private WorkerDtos() {
    }

    /** A message handed to the worker by the queue-service. */
    public record Task(String id, String queue, String payload, int priority, int attempts) {
    }

    public record WorkerStats(String workerId, boolean paused, int inFlight, int maxConcurrency,
                              int availablePermits, long processedOk, long processedFail,
                              long totalProcessed, String uptimeSeconds) {
    }

    // ---- wire DTOs towards queue-service (subset of its contract) ----

    public record ClaimWire(int max, String workerId) {
    }

    public record WorkerActionWire(String workerId, String error) {
    }

    public record Envelope<T>(boolean success, String message, T data) {
    }

    public record ClaimData(String queue, String workerId, int claimed, List<MessageWire> messages) {
    }

    public record MessageWire(String id, String queue, String payload, int priority, String status,
                              int attempts, String error, String claimedBy, String nextAttemptAt, String createdAt) {
    }
}

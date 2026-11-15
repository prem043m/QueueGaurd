package com.queueguard.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class QueueDtos {

    private QueueDtos() {
    }

    public record CreateQueueRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 500) String description,
            @Min(1) @Max(1024) Integer maxConcurrency,
            @Min(1) @Max(50) Integer maxAttempts) {
    }

    public record QueueView(String name, String description, int maxConcurrency, int maxAttempts,
                            long pending, long claimed, long done, long dead, long readyDepth, String createdAt) {
    }

    public record EnqueueRequest(
            @NotBlank String payload,
            @Min(0) @Max(9) Integer priority,
            @Size(max = 120) String idempotencyKey,
            @Min(0) Long delayMs) {
    }

    public record EnqueueResponse(String messageId, boolean duplicate, String queue, String status) {
    }

    public record MessageView(String id, String queue, String payload, int priority, String status,
                              int attempts, String error, String claimedBy, String nextAttemptAt, String createdAt) {
    }

    public record ClaimRequest(@Min(1) @Max(64) Integer max, String workerId) {
    }

    public record ClaimResponse(String queue, String workerId, int claimed, List<MessageView> messages) {
    }

    public record WorkerActionRequest(String workerId, String error) {
    }

    public record StatsResponse(String queue, long pending, long claimed, long done, long dead,
                                long readyDepth, int maxConcurrency, int maxAttempts) {
    }
}

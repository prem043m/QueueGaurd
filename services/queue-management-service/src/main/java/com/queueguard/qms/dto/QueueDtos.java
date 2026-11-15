package com.queueguard.qms.dto;

import com.queueguard.qms.domain.*;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Set;

/**
 * Data Transfer Objects for Queue Management API.
 */
public final class QueueDtos {
    
    private QueueDtos() {}
    
    // ==================== Queue Configuration DTOs ====================
    
    public record CreateQueueConfigurationRequest(
            @NotBlank @Size(min = 2, max = 255) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(min = 2, max = 50) String queueCode,
            @NotNull QueueType queueType,
            @NotNull OrderingPolicy orderingPolicy,
            Integer maximumCapacity,
            boolean priorityEnabled,
            Integer estimatedServiceDurationSeconds
    ) {}
    
    public record UpdateQueueConfigurationRequest(
            @Size(min = 2, max = 255) String name,
            @Size(max = 1000) String description,
            Integer maximumCapacity,
            Boolean priorityEnabled,
            Integer estimatedServiceDurationSeconds
    ) {}
    
    public record QueueConfigurationView(
            Long id,
            Long organizationId,
            String name,
            String description,
            String queueCode,
            QueueType queueType,
            OrderingPolicy orderingPolicy,
            QueueStatus status,
            Integer maximumCapacity,
            boolean priorityEnabled,
            Integer estimatedServiceDurationSeconds,
            Long createdBy,
            String createdAt,
            String updatedAt,
            Long version
    ) {
        public static QueueConfigurationView from(QueueConfiguration qc) {
            return new QueueConfigurationView(
                    qc.getId(),
                    qc.getOrganizationId(),
                    qc.getName(),
                    qc.getDescription(),
                    qc.getQueueCode(),
                    qc.getQueueType(),
                    qc.getOrderingPolicy(),
                    qc.getStatus(),
                    qc.getMaximumCapacity(),
                    qc.isPriorityEnabled(),
                    qc.getEstimatedServiceDurationSeconds(),
                    qc.getCreatedBy(),
                    qc.getCreatedAt().toString(),
                    qc.getUpdatedAt().toString(),
                    qc.getVersion()
            );
        }
    }
    
    // ==================== Queue Entry DTOs ====================
    
    public record CreateQueueEntryRequest(
            @Size(max = 255) String externalReference,
            Long participantUserId,
            @NotBlank @Size(min = 2, max = 255) String participantName,
            @Email @Size(max = 255) String participantEmail,
            Integer priority
    ) {}
    
    public record QueueEntryView(
            Long id,
            Long organizationId,
            Long queueConfigurationId,
            String tokenNumber,
            String externalReference,
            Long participantUserId,
            String participantName,
            String participantEmail,
            Integer priority,
            QueueEntryStatus status,
            String joinedAt,
            String calledAt,
            String serviceStartedAt,
            String completedAt,
            String cancelledAt,
            Integer estimatedWaitSeconds,
            String createdAt,
            String updatedAt,
            Long version
    ) {
        public static QueueEntryView from(QueueEntry qe) {
            return new QueueEntryView(
                    qe.getId(),
                    qe.getOrganizationId(),
                    qe.getQueueConfigurationId(),
                    qe.getTokenNumber(),
                    qe.getExternalReference(),
                    qe.getParticipantUserId(),
                    qe.getParticipantName(),
                    qe.getParticipantEmail(),
                    qe.getPriority(),
                    qe.getStatus(),
                    qe.getJoinedAt().toString(),
                    qe.getCalledAt() != null ? qe.getCalledAt().toString() : null,
                    qe.getServiceStartedAt() != null ? qe.getServiceStartedAt().toString() : null,
                    qe.getCompletedAt() != null ? qe.getCompletedAt().toString() : null,
                    qe.getCancelledAt() != null ? qe.getCancelledAt().toString() : null,
                    qe.getEstimatedWaitSeconds(),
                    qe.getCreatedAt().toString(),
                    qe.getUpdatedAt().toString(),
                    qe.getVersion()
            );
        }
    }
    
    public record QueueEntryPositionView(
            Long entryId,
            String tokenNumber,
            QueueEntryStatus status,
            Integer position,
            Integer entriesAhead,
            Integer totalWaiting,
            Integer estimatedWaitSeconds
    ) {}
    
    public record QueueEntryListView(
            Long id,
            String tokenNumber,
            String participantName,
            QueueEntryStatus status,
            Integer priority,
            String joinedAt,
            String calledAt,
            String serviceStartedAt
    ) {
        public static QueueEntryListView from(QueueEntry qe) {
            return new QueueEntryListView(
                    qe.getId(),
                    qe.getTokenNumber(),
                    qe.getParticipantName(),
                    qe.getStatus(),
                    qe.getPriority(),
                    qe.getJoinedAt().toString(),
                    qe.getCalledAt() != null ? qe.getCalledAt().toString() : null,
                    qe.getServiceStartedAt() != null ? qe.getServiceStartedAt().toString() : null
            );
        }
    }
    
    // ==================== Queue Assignment DTOs ====================
    
    public record QueueAssignmentView(
            Long id,
            Long organizationId,
            Long queueEntryId,
            Long operatorUserId,
            String servicePoint,
            QueueAssignmentStatus status,
            String assignedAt,
            String releasedAt,
            String createdAt,
            Long version
    ) {
        public static QueueAssignmentView from(QueueAssignment qa) {
            return new QueueAssignmentView(
                    qa.getId(),
                    qa.getOrganizationId(),
                    qa.getQueueEntryId(),
                    qa.getOperatorUserId(),
                    qa.getServicePoint(),
                    qa.getStatus(),
                    qa.getAssignedAt().toString(),
                    qa.getReleasedAt() != null ? qa.getReleasedAt().toString() : null,
                    qa.getCreatedAt().toString(),
                    qa.getVersion()
            );
        }
    }
    
    // ==================== Queue Event DTOs ====================
    
    public record QueueEventView(
            Long id,
            Long organizationId,
            Long queueConfigurationId,
            Long queueEntryId,
            QueueEventType eventType,
            Long actorUserId,
            String eventPayload,
            String occurredAt
    ) {
        public static QueueEventView from(QueueEvent qe) {
            return new QueueEventView(
                    qe.getId(),
                    qe.getOrganizationId(),
                    qe.getQueueConfigurationId(),
                    qe.getQueueEntryId(),
                    qe.getEventType(),
                    qe.getActorUserId(),
                    qe.getEventPayload(),
                    qe.getOccurredAt().toString()
            );
        }
    }
    
    // ==================== Queue Statistics DTOs ====================
    
    public record QueueStatisticsView(
            Long queueConfigurationId,
            String queueName,
            String queueCode,
            QueueStatus status,
            Integer totalEntries,
            Integer waitingCount,
            Integer calledCount,
            Integer inServiceCount,
            Integer completedCount,
            Integer cancelledCount,
            Integer noShowCount,
            Integer skippedCount,
            Double averageServiceDurationSeconds,
            Integer currentCapacityUsage,
            Integer maximumCapacity
    ) {}
    
    public record QueueCurrentStateView(
            Long queueConfigurationId,
            String queueName,
            QueueStatus status,
            QueueEntryListView currentlyServing,
            QueueEntryListView nextInQueue,
            Integer waitingCount
    ) {}
    
    // ==================== Operator Call DTOs ====================
    
    public record CallNextRequest(
            @Size(max = 100) String servicePoint,
            @NotBlank @Size(max = 50) String idempotencyKey
    ) {}
    
    public record CallNextResponse(
            QueueEntryView entry,
            QueueAssignmentView assignment,
            Integer position,
            String queueStatus
    ) {}
    
    public record StartServiceRequest(
            @Size(max = 100) String servicePoint,
            @NotBlank @Size(max = 50) String idempotencyKey
    ) {}
    
    public record AssignOperatorRequest(
            @NotNull Long operatorUserId,
            @Size(max = 100) String servicePoint,
            @NotBlank @Size(max = 50) String idempotencyKey
    ) {}
    
    // ==================== Outbox Event DTOs ====================
    
    public record OutboxEventView(
            Long id,
            Long organizationId,
            String aggregateType,
            Long aggregateId,
            String eventType,
            String payload,
            String createdAt,
            String publishedAt,
            OutboxEventStatus processingStatus,
            Integer retryCount,
            String lastError,
            String idempotencyKey,
            Long version
    ) {
        public static OutboxEventView from(OutboxEvent oe) {
            return new OutboxEventView(
                    oe.getId(),
                    oe.getOrganizationId(),
                    oe.getAggregateType(),
                    oe.getAggregateId(),
                    oe.getEventType(),
                    oe.getPayload(),
                    oe.getCreatedAt().toString(),
                    oe.getPublishedAt() != null ? oe.getPublishedAt().toString() : null,
                    oe.getProcessingStatus(),
                    oe.getRetryCount(),
                    oe.getLastError(),
                    oe.getIdempotencyKey(),
                    oe.getVersion()
            );
        }
    }
    
    // ==================== Token DTOs ====================
    
    public record TokenInfoView(
            String tokenNumber,
            Long queueConfigurationId,
            String queueName,
            String queueCode,
            QueueEntryStatus status,
            Integer position,
            Integer entriesAhead,
            Integer estimatedWaitSeconds,
            String joinedAt
    ) {}
}

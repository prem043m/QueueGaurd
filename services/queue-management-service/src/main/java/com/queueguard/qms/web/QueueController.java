package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.qms.domain.QueueEntryStatus;
import com.queueguard.qms.dto.QueueDtos.*;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.QueueService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for business queue management operations.
 * 
 * Base path: /api/v1/organizations/{orgId}/queues
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/queues")
public class QueueController {
    
    private final QueueService queueService;
    
    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }
    
    // ==================== Queue Configuration Endpoints ====================
    
    @PostMapping
    public ResponseEntity<ApiResponse<QueueConfigurationView>> createQueueConfig(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @Valid @RequestBody CreateQueueConfigurationRequest request) {
        QueueConfigurationView result = queueService.createQueueConfig(actor, orgId, request);
        return ResponseEntity.ok(ApiResponse.ok("Queue configuration created", result));
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<QueueConfigurationView>>> listQueueConfigs(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId) {
        List<QueueConfigurationView> result = queueService.listQueueConfigs(actor, orgId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @GetMapping("/{queueId}")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> getQueueConfig(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueConfigurationView result = queueService.getQueueConfig(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @PatchMapping("/{queueId}")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> updateQueueConfig(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @Valid @RequestBody UpdateQueueConfigurationRequest request) {
        QueueConfigurationView result = queueService.updateQueueConfig(actor, orgId, queueId, request);
        return ResponseEntity.ok(ApiResponse.ok("Queue configuration updated", result));
    }
    
    @PostMapping("/{queueId}/open")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> openQueue(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueConfigurationView result = queueService.openQueue(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok("Queue opened", result));
    }
    
    @PostMapping("/{queueId}/pause")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> pauseQueue(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueConfigurationView result = queueService.pauseQueue(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok("Queue paused", result));
    }
    
    @PostMapping("/{queueId}/resume")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> resumeQueue(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueConfigurationView result = queueService.resumeQueue(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok("Queue resumed", result));
    }
    
    @PostMapping("/{queueId}/close")
    public ResponseEntity<ApiResponse<QueueConfigurationView>> closeQueue(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueConfigurationView result = queueService.closeQueue(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok("Queue closed", result));
    }
    
    // ==================== Queue Entry Endpoints ====================
    
    @PostMapping("/{queueId}/entries")
    public ResponseEntity<ApiResponse<QueueEntryView>> createQueueEntry(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @Valid @RequestBody CreateQueueEntryRequest request) {
        // Use registerParticipant to ensure proper authorization for external participants
        QueueEntryView result = queueService.registerParticipant(actor, orgId, queueId, request);
        return ResponseEntity.ok(ApiResponse.ok("Queue entry created", result));
    }
    
    @GetMapping("/{queueId}/entries")
    public ResponseEntity<ApiResponse<List<QueueEntryListView>>> listQueueEntries(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @RequestParam(required = false) QueueEntryStatus status,
            @PageableDefault(size = 20, page = 0) Pageable pageable) {
        List<QueueEntryListView> result = queueService.listQueueEntries(
                actor, orgId, queueId, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @GetMapping("/{queueId}/entries/{entryId}")
    public ResponseEntity<ApiResponse<QueueEntryView>> getQueueEntry(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.getQueueEntry(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @GetMapping("/{queueId}/entries/{entryId}/position")
    public ResponseEntity<ApiResponse<QueueEntryPositionView>> getQueueEntryPosition(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryPositionView result = queueService.getQueueEntryPosition(
                actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/cancel")
    public ResponseEntity<ApiResponse<QueueEntryView>> cancelQueueEntry(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.cancelEntry(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok("Queue entry cancelled", result));
    }
    
    // ==================== Operator Operations ====================
    
    @PostMapping("/{queueId}/call-next")
    public ResponseEntity<ApiResponse<CallNextResponse>> callNext(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @Valid @RequestBody CallNextRequest request) {
        CallNextResponse result = queueService.callNext(actor, orgId, queueId, request);
        return ResponseEntity.ok(ApiResponse.ok("Next entry called", result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/start")
    public ResponseEntity<ApiResponse<QueueEntryView>> startService(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId,
            @Valid @RequestBody StartServiceRequest request) {
        QueueEntryView result = queueService.startService(actor, orgId, queueId, entryId, request);
        return ResponseEntity.ok(ApiResponse.ok("Service started", result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/complete")
    public ResponseEntity<ApiResponse<QueueEntryView>> completeService(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.completeService(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok("Service completed", result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/no-show")
    public ResponseEntity<ApiResponse<QueueEntryView>> markNoShow(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.markNoShow(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok("Entry marked as no-show", result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/requeue")
    public ResponseEntity<ApiResponse<QueueEntryView>> requeueEntry(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.requeueEntry(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok("Entry requeued", result));
    }
    
    @PostMapping("/{queueId}/entries/{entryId}/skip")
    public ResponseEntity<ApiResponse<QueueEntryView>> skipEntry(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId,
            @PathVariable Long entryId) {
        QueueEntryView result = queueService.skipEntry(actor, orgId, queueId, entryId);
        return ResponseEntity.ok(ApiResponse.ok("Entry skipped", result));
    }
    
    // ==================== Statistics Endpoints ====================
    
    @GetMapping("/{queueId}/stats")
    public ResponseEntity<ApiResponse<QueueStatisticsView>> getQueueStatistics(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueStatisticsView result = queueService.getQueueStatistics(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
    
    @GetMapping("/{queueId}/current")
    public ResponseEntity<ApiResponse<QueueCurrentStateView>> getQueueCurrentState(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actor,
            @PathVariable Long orgId,
            @PathVariable Long queueId) {
        QueueCurrentStateView result = queueService.getQueueCurrentState(actor, orgId, queueId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}

package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.QueueConfiguration;
import com.queueguard.qms.domain.QueueEntry;
import com.queueguard.qms.domain.QueueEntryStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized queue state machine for validating state transitions.
 * 
 * Valid transitions:
 * - WAITING -> CALLED (call next)
 * - WAITING -> CANCELLED (user cancels)
 * - CALLED -> IN_SERVICE (service starts)
 * - CALLED -> NO_SHOW (no show)
 * - CALLED -> WAITING (requeue)
 * - IN_SERVICE -> COMPLETED (service completes)
 * - IN_SERVICE -> SKIPPED (skip entry)
 * 
 * Terminal states (cannot transition out):
 * - COMPLETED
 * - CANCELLED
 * - NO_SHOW
 * - SKIPPED
 */
public final class QueueStateMachine {
    
    private QueueStateMachine() {}
    
    // Define valid transitions
    private static final Map<QueueEntryStatus, Set<QueueEntryStatus>> VALID_TRANSITIONS = new EnumMap<>(QueueEntryStatus.class);
    
    static {
        // WAITING can go to CALLED or CANCELLED
        VALID_TRANSITIONS.put(QueueEntryStatus.WAITING, 
                EnumSet.of(QueueEntryStatus.CALLED, QueueEntryStatus.CANCELLED));
        
        // CALLED can go to IN_SERVICE, NO_SHOW, or WAITING (requeue)
        VALID_TRANSITIONS.put(QueueEntryStatus.CALLED, 
                EnumSet.of(QueueEntryStatus.IN_SERVICE, QueueEntryStatus.NO_SHOW, QueueEntryStatus.WAITING));
        
        // IN_SERVICE can go to COMPLETED or SKIPPED
        VALID_TRANSITIONS.put(QueueEntryStatus.IN_SERVICE, 
                EnumSet.of(QueueEntryStatus.COMPLETED, QueueEntryStatus.SKIPPED));
        
        // Terminal states have no valid transitions
        VALID_TRANSITIONS.put(QueueEntryStatus.COMPLETED, EnumSet.noneOf(QueueEntryStatus.class));
        VALID_TRANSITIONS.put(QueueEntryStatus.CANCELLED, EnumSet.noneOf(QueueEntryStatus.class));
        VALID_TRANSITIONS.put(QueueEntryStatus.NO_SHOW, EnumSet.noneOf(QueueEntryStatus.class));
        VALID_TRANSITIONS.put(QueueEntryStatus.SKIPPED, EnumSet.noneOf(QueueEntryStatus.class));
    }
    
    /**
     * Validates if a state transition is allowed.
     * 
     * @param currentStatus the current status
     * @param targetStatus the target status
     * @return true if the transition is valid
     */
    public static boolean isValidTransition(QueueEntryStatus currentStatus, QueueEntryStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        
        Set<QueueEntryStatus> validTargets = VALID_TRANSITIONS.get(currentStatus);
        return validTargets != null && validTargets.contains(targetStatus);
    }
    
    /**
     * Validates and performs a state transition.
     * 
     * @param entry the queue entry to transition
     * @param targetStatus the target status
     * @param queueConfig the queue configuration (for validation)
     * @throws BusinessException if the transition is invalid
     */
    public static void transition(QueueEntry entry, QueueEntryStatus targetStatus, QueueConfiguration queueConfig) {
        if (entry == null || targetStatus == null) {
            throw new BusinessException(400, "INVALID_TRANSITION", "Entry and target status must not be null");
        }
        
        QueueEntryStatus currentStatus = entry.getStatus();
        
        // Check if transition is valid
        if (!isValidTransition(currentStatus, targetStatus)) {
            throw new BusinessException(409, "INVALID_STATE_TRANSITION", 
                    String.format("Cannot transition from %s to %s", currentStatus, targetStatus));
        }
        
        // Validate queue configuration state
        if (queueConfig != null) {
            validateQueueConfigForTransition(queueConfig, targetStatus);
        }
        
        // Check for terminal state
        if (currentStatus.isTerminal()) {
            throw new BusinessException(409, "TERMINAL_STATE", 
                    String.format("Entry in terminal state %s cannot be transitioned", currentStatus));
        }
        
        // Specific validation rules
        validateSpecificTransition(entry, currentStatus, targetStatus);
    }
    
    /**
     * Validates queue configuration state for the transition.
     */
    private static void validateQueueConfigForTransition(QueueConfiguration config, QueueEntryStatus targetStatus) {
        // Cannot call entries if queue is closed or archived
        if (targetStatus == QueueEntryStatus.CALLED && config.isClosed()) {
            throw new BusinessException(409, "QUEUE_CLOSED", 
                    "Cannot call entries from a closed queue");
        }
        
        // Cannot call entries if queue is paused
        if (targetStatus == QueueEntryStatus.CALLED && config.isPaused()) {
            throw new BusinessException(409, "QUEUE_PAUSED", 
                    "Cannot call entries from a paused queue");
        }
    }
    
    /**
     * Validates specific transition rules.
     */
    private static void validateSpecificTransition(QueueEntry entry, 
                                                    QueueEntryStatus currentStatus, 
                                                    QueueEntryStatus targetStatus) {
        // Cannot call an already called entry
        if (targetStatus == QueueEntryStatus.CALLED && currentStatus == QueueEntryStatus.CALLED) {
            throw new BusinessException(409, "ALREADY_CALLED", "Entry is already called");
        }
        
        // Cannot start service on a non-called entry
        if (targetStatus == QueueEntryStatus.IN_SERVICE && !currentStatus.isCalled()) {
            throw new BusinessException(409, "NOT_CALLED", 
                    "Can only start service on a CALLED entry");
        }
        
        // Cannot complete an entry that's not in service
        if (targetStatus == QueueEntryStatus.COMPLETED && !currentStatus.isInService()) {
            throw new BusinessException(409, "NOT_IN_SERVICE", 
                    "Can only complete an entry that is IN_SERVICE");
        }
        
        // Cannot mark as no-show an entry that's already in service
        if (targetStatus == QueueEntryStatus.NO_SHOW && currentStatus.isInService()) {
            throw new BusinessException(409, "ALREADY_IN_SERVICE", 
                    "Cannot mark as no-show an entry that is already IN_SERVICE");
        }
        
        // Cannot cancel a terminal entry
        if (targetStatus == QueueEntryStatus.CANCELLED && currentStatus.isTerminal()) {
            throw new BusinessException(409, "ALREADY_TERMINAL", 
                    "Cannot cancel an entry that is already in a terminal state");
        }
    }
    
    /**
     * Validates that an entry can be called.
     */
    public static void validateCanCall(QueueEntry entry, QueueConfiguration queueConfig) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isEligibleForCall()) {
            throw new BusinessException(409, "NOT_ELIGIBLE_FOR_CALL", 
                    String.format("Entry in status %s cannot be called", entry.getStatus()));
        }
        
        if (queueConfig == null) {
            throw new BusinessException(404, "QUEUE_NOT_FOUND", "Queue configuration not found");
        }
        
        if (queueConfig.isClosed()) {
            throw new BusinessException(409, "QUEUE_CLOSED", "Queue is closed");
        }
        
        if (queueConfig.isPaused()) {
            throw new BusinessException(409, "QUEUE_PAUSED", "Queue is paused");
        }
    }
    
    /**
     * Validates that an entry can be started (service).
     */
    public static void validateCanStart(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isCalled()) {
            throw new BusinessException(409, "NOT_CALLED", 
                    "Can only start service on a CALLED entry");
        }
    }
    
    /**
     * Validates that an entry can be completed.
     */
    public static void validateCanComplete(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isInService()) {
            throw new BusinessException(409, "NOT_IN_SERVICE", 
                    "Can only complete an entry that is IN_SERVICE");
        }
    }
    
    /**
     * Validates that an entry can be cancelled.
     */
    public static void validateCanCancel(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (entry.isTerminal()) {
            throw new BusinessException(409, "ALREADY_TERMINAL", 
                    "Cannot cancel an entry that is already in a terminal state");
        }
        
        if (!entry.isWaiting()) {
            throw new BusinessException(409, "NOT_WAITING", 
                    "Can only cancel entries that are WAITING");
        }
    }
    
    /**
     * Validates that an entry can be marked as no-show.
     */
    public static void validateCanMarkNoShow(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isCalled()) {
            throw new BusinessException(409, "NOT_CALLED", 
                    "Can only mark as no-show an entry that is CALLED");
        }
        
        if (entry.isInService()) {
            throw new BusinessException(409, "ALREADY_IN_SERVICE", 
                    "Cannot mark as no-show an entry that is already IN_SERVICE");
        }
    }
    
    /**
     * Validates that an entry can be requeued.
     */
    public static void validateCanRequeue(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isCalled()) {
            throw new BusinessException(409, "NOT_CALLED", 
                    "Can only requeue an entry that is CALLED");
        }
    }
    
    /**
     * Validates that an entry can be skipped.
     */
    public static void validateCanSkip(QueueEntry entry) {
        if (entry == null) {
            throw new BusinessException(404, "ENTRY_NOT_FOUND", "Queue entry not found");
        }
        
        if (!entry.isCalled() && !entry.isInService()) {
            throw new BusinessException(409, "NOT_CALLED_OR_IN_SERVICE", 
                    "Can only skip an entry that is CALLED or IN_SERVICE");
        }
    }
}

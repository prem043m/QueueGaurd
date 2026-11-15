package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.qms.domain.QueueConfiguration;
import com.queueguard.qms.domain.QueueEntry;
import com.queueguard.qms.domain.QueueEntryStatus;
import com.queueguard.qms.domain.QueueStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueueStateMachine state transition validation.
 */
class QueueStateMachineTest {
    
    @Test
    void testValidTransitions() {
        // WAITING -> CALLED
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.WAITING, QueueEntryStatus.CALLED));
        
        // WAITING -> CANCELLED
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.WAITING, QueueEntryStatus.CANCELLED));
        
        // CALLED -> IN_SERVICE
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.CALLED, QueueEntryStatus.IN_SERVICE));
        
        // CALLED -> NO_SHOW
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.CALLED, QueueEntryStatus.NO_SHOW));
        
        // CALLED -> WAITING (requeue)
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.CALLED, QueueEntryStatus.WAITING));
        
        // IN_SERVICE -> COMPLETED
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.IN_SERVICE, QueueEntryStatus.COMPLETED));
        
        // IN_SERVICE -> SKIPPED
        assertTrue(QueueStateMachine.isValidTransition(QueueEntryStatus.IN_SERVICE, QueueEntryStatus.SKIPPED));
    }
    
    @Test
    void testInvalidTransitions() {
        // Cannot go back from completed states
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.COMPLETED, QueueEntryStatus.WAITING));
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.CANCELLED, QueueEntryStatus.CALLED));
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.NO_SHOW, QueueEntryStatus.CALLED));
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.SKIPPED, QueueEntryStatus.CALLED));
        
        // Cannot skip from WAITING
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.WAITING, QueueEntryStatus.SKIPPED));
        
        // Cannot call from IN_SERVICE
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.IN_SERVICE, QueueEntryStatus.CALLED));
        
        // Cannot complete from CALLED
        assertFalse(QueueStateMachine.isValidTransition(QueueEntryStatus.CALLED, QueueEntryStatus.COMPLETED));
    }
    
    @Test
    void testCanCallValidation() {
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueConfiguration openConfig = new QueueConfiguration();
        openConfig.setStatus(QueueStatus.OPEN);
        
        QueueConfiguration closedConfig = new QueueConfiguration();
        closedConfig.setStatus(QueueStatus.CLOSED);
        
        QueueConfiguration pausedConfig = new QueueConfiguration();
        pausedConfig.setStatus(QueueStatus.PAUSED);
        
        // Valid: WAITING entry in OPEN queue
        assertDoesNotThrow(() -> 
            QueueStateMachine.validateCanCall(waitingEntry, openConfig));
        
        // Valid: CALLED entry can be re-queued (validateCanCall should allow it for requeue)
        // Actually, the validateCanCall checks if isEligibleForCall which includes CALLED
        // So this should not throw. Let's test with IN_SERVICE instead
        QueueEntry inServiceEntry = new QueueEntry();
        inServiceEntry.setStatus(QueueEntryStatus.IN_SERVICE);
        
        // Invalid: IN_SERVICE entry should not be callable
        assertThrows(BusinessException.class, () -> 
            QueueStateMachine.validateCanCall(inServiceEntry, openConfig));
        
        // Invalid: queue is closed
        assertThrows(BusinessException.class, () -> 
            QueueStateMachine.validateCanCall(waitingEntry, closedConfig));
        
        // Invalid: queue is paused
        assertThrows(BusinessException.class, () -> 
            QueueStateMachine.validateCanCall(waitingEntry, pausedConfig));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> 
            QueueStateMachine.validateCanCall(null, openConfig));
        
        // Invalid: null config
        assertThrows(BusinessException.class, () -> 
            QueueStateMachine.validateCanCall(waitingEntry, null));
    }
    
    @Test
    void testCanStartValidation() {
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        // Valid: CALLED entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanStart(calledEntry));
        
        // Invalid: WAITING entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanStart(waitingEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanStart(null));
    }
    
    @Test
    void testCanCompleteValidation() {
        QueueEntry inServiceEntry = new QueueEntry();
        inServiceEntry.setStatus(QueueEntryStatus.IN_SERVICE);
        
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        // Valid: IN_SERVICE entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanComplete(inServiceEntry));
        
        // Invalid: CALLED entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanComplete(calledEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanComplete(null));
    }
    
    @Test
    void testCanCancelValidation() {
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueEntry completedEntry = new QueueEntry();
        completedEntry.setStatus(QueueEntryStatus.COMPLETED);
        
        // Valid: WAITING entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanCancel(waitingEntry));
        
        // Invalid: CALLED entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanCancel(calledEntry));
        
        // Invalid: COMPLETED entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanCancel(completedEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanCancel(null));
    }
    
    @Test
    void testCanMarkNoShowValidation() {
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueEntry inServiceEntry = new QueueEntry();
        inServiceEntry.setStatus(QueueEntryStatus.IN_SERVICE);
        
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        // Valid: CALLED entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanMarkNoShow(calledEntry));
        
        // Invalid: IN_SERVICE entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanMarkNoShow(inServiceEntry));
        
        // Invalid: WAITING entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanMarkNoShow(waitingEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanMarkNoShow(null));
    }
    
    @Test
    void testCanRequeueValidation() {
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        // Valid: CALLED entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanRequeue(calledEntry));
        
        // Invalid: WAITING entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanRequeue(waitingEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanRequeue(null));
    }
    
    @Test
    void testCanSkipValidation() {
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        
        QueueEntry inServiceEntry = new QueueEntry();
        inServiceEntry.setStatus(QueueEntryStatus.IN_SERVICE);
        
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        
        // Valid: CALLED entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanSkip(calledEntry));
        
        // Valid: IN_SERVICE entry
        assertDoesNotThrow(() -> QueueStateMachine.validateCanSkip(inServiceEntry));
        
        // Invalid: WAITING entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanSkip(waitingEntry));
        
        // Invalid: null entry
        assertThrows(BusinessException.class, () -> QueueStateMachine.validateCanSkip(null));
    }
}

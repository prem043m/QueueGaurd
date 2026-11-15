package com.queueguard.qms.domain;

/**
 * Status of a queue entry in its lifecycle.
 */
public enum QueueEntryStatus {
    WAITING,
    CALLED,
    IN_SERVICE,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    SKIPPED;
    
    public boolean isWaiting() {
        return this == WAITING;
    }
    
    public boolean isCalled() {
        return this == CALLED;
    }
    
    public boolean isInService() {
        return this == IN_SERVICE;
    }
    
    public boolean isCompleted() {
        return this == COMPLETED;
    }
    
    public boolean isCancelled() {
        return this == CANCELLED;
    }
    
    public boolean isNoShow() {
        return this == NO_SHOW;
    }
    
    public boolean isSkipped() {
        return this == SKIPPED;
    }
    
    public boolean isTerminal() {
        return isCompleted() || isCancelled() || isNoShow() || isSkipped();
    }
    
    public boolean isEligibleForCall() {
        return isWaiting() || isCalled();
    }
    
    public boolean isActive() {
        return isCalled() || isInService();
    }
}

package com.queueguard.qms.domain;

/**
 * Types of business queue events for auditing.
 */
public enum QueueEventType {
    QUEUE_CREATED,
    QUEUE_UPDATED,
    QUEUE_OPENED,
    QUEUE_PAUSED,
    QUEUE_RESUMED,
    QUEUE_CLOSED,
    QUEUE_ARCHIVED,
    ENTRY_REGISTERED,
    ENTRY_CALLED,
    ENTRY_STARTED,
    ENTRY_COMPLETED,
    ENTRY_CANCELLED,
    ENTRY_NO_SHOW,
    ENTRY_SKIPPED,
    ENTRY_REQUEUED,
    ASSIGNMENT_CREATED,
    ASSIGNMENT_RELEASED
}

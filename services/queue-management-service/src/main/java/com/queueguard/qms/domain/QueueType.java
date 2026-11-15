package com.queueguard.qms.domain;

/**
 * Type of business queue. Generic types allow for future extensions.
 */
public enum QueueType {
    GENERIC,
    COUNTER,
    APPOINTMENT,
    WALK_IN
}

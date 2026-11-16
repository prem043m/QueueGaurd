package com.queueguard.qms.domain;

public enum InboxEventStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    RETRYABLE,
    DEAD_LETTERED
}

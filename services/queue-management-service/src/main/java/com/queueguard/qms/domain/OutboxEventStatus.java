package com.queueguard.qms.domain;

/**
 * Processing status of an outbox event.
 */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

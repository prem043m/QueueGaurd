package com.queueguard.qms.repo;

/**
 * Database aggregate projection used by organization analytics.
 */
public interface StatusCount {
    String getStatus();
    Long getCount();
}

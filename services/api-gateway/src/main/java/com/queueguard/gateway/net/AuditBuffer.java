package com.queueguard.gateway.net;

import com.queueguard.gateway.entity.AuditLog;
import com.queueguard.gateway.repo.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Attack-proof audit writer: entries land in a bounded in-memory queue and are
 * flushed to PostgreSQL in batches. If the database is slow (or an attacker is
 * flooding us), the queue drops the oldest noise instead of blocking request threads.
 */
@Component
public class AuditBuffer {

    private static final Logger log = LoggerFactory.getLogger(AuditBuffer.class);
    private static final int CAPACITY = 10_000;

    private final BlockingQueue<AuditLog> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AuditLogRepository repo;

    public AuditBuffer(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(AuditLog entry) {
        if (!queue.offer(entry)) {
            log.debug("Audit buffer full - dropping entry for {}", entry.getIp());
        }
    }

    @Scheduled(fixedDelayString = "${queueguard.audit-flush-ms:2000}")
    public void flush() {
        List<AuditLog> batch = new ArrayList<>(500);
        queue.drainTo(batch, 500);
        if (!batch.isEmpty()) {
            try {
                repo.saveAll(batch);
            } catch (Exception e) {
                log.warn("Audit flush failed ({} entries): {}", batch.size(), e.getMessage());
            }
        }
    }
}

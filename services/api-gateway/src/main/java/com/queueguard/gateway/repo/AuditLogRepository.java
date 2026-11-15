package com.queueguard.gateway.repo;

import com.queueguard.gateway.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
    List<AuditLog> findTop100ByIpOrderByCreatedAtDesc(String ip);

    long countByIpAndCreatedAtAfter(String ip, Instant after);
}

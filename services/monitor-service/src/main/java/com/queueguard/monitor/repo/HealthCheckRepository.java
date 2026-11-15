package com.queueguard.monitor.repo;

import com.queueguard.monitor.entity.MonitorEntities.HealthCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, Long> {
    Optional<HealthCheck> findTopByServiceOrderByCheckedAtDesc(String service);
    List<HealthCheck> findTop50ByOrderByCheckedAtDesc();

    @Modifying
    @Query("DELETE FROM HealthCheck h WHERE h.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

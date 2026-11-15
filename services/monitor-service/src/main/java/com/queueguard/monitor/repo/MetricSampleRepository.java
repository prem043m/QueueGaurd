package com.queueguard.monitor.repo;

import com.queueguard.monitor.entity.MonitorEntities.MetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {
    Optional<MetricSample> findTopByServiceOrderByReceivedAtDesc(String service);
    List<MetricSample> findTop50ByOrderByReceivedAtDesc();

    @Modifying
    @Query("DELETE FROM MetricSample m WHERE m.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

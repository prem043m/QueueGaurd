package com.queueguard.monitor.repo;

import com.queueguard.monitor.entity.MonitorEntities.LoadSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LoadSnapshotRepository extends JpaRepository<LoadSnapshot, Long> {
    Optional<LoadSnapshot> findTopByOrderByCreatedAtDesc();
    List<LoadSnapshot> findTop50ByOrderByCreatedAtDesc();

    @Modifying
    @Query("DELETE FROM LoadSnapshot l WHERE l.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

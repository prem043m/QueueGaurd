package com.queueguard.qms.repo;

import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlacementDriveRepository extends JpaRepository<PlacementDrive, Long> {
    Optional<PlacementDrive> findByIdAndOrganizationId(Long id, Long organizationId);

    List<PlacementDrive> findByOrganizationIdOrderById(Long organizationId);

    List<PlacementDrive> findByOrganizationIdAndStatusOrderById(
            Long organizationId, PlacementDriveStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select drive from PlacementDrive drive "
            + "where drive.id = :driveId and drive.organizationId = :organizationId")
    Optional<PlacementDrive> findByIdAndOrganizationIdForUpdate(
            @Param("driveId") Long driveId,
            @Param("organizationId") Long organizationId);

    @Query(value = """
            select count(*) from placement_drives
            where organization_id = :organizationId
              and (:fromAt is null or created_at >= :fromAt)
              and (:toAt is null or created_at < :toAt)
            """, nativeQuery = true)
    long countForAnalytics(@Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    @Query(value = """
            select status as status, count(*) as count from placement_drives
            where organization_id = :organizationId
              and (:fromAt is null or created_at >= :fromAt)
              and (:toAt is null or created_at < :toAt)
            group by status order by status
            """, nativeQuery = true)
    Collection<StatusCount> countStatusesForAnalytics(
            @Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);
}

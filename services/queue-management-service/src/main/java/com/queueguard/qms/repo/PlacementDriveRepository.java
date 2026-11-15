package com.queueguard.qms.repo;

import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
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
}

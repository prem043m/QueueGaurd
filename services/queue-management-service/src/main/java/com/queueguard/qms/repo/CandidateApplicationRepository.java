package com.queueguard.qms.repo;

import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateApplication;
import com.queueguard.qms.domain.EligibilityStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateApplicationRepository
        extends JpaRepository<CandidateApplication, Long>, ApplicationNumberAllocator {
    Optional<CandidateApplication> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query("select application.placementDriveId from CandidateApplication application "
            + "where application.id = :applicationId "
            + "and application.organizationId = :organizationId")
    Optional<Long> findPlacementDriveIdByIdAndOrganizationId(
            @Param("applicationId") Long applicationId,
            @Param("organizationId") Long organizationId);

    List<CandidateApplication> findByOrganizationIdAndPlacementDriveIdOrderByApplicationNumber(
            Long organizationId, Long placementDriveId);

    List<CandidateApplication> findByOrganizationIdAndCandidateProfileIdOrderByAppliedAtDesc(
            Long organizationId, Long candidateProfileId);

    List<CandidateApplication> findByOrganizationIdAndApplicationStatusOrderByAppliedAtDesc(
            Long organizationId, ApplicationStatus applicationStatus);

    List<CandidateApplication> findByOrganizationIdAndPlacementDriveIdAndApplicationStatusOrderByApplicationNumber(
            Long organizationId, Long placementDriveId, ApplicationStatus applicationStatus);

    List<CandidateApplication>
            findByOrganizationIdAndPlacementDriveIdAndEligibilityStatusOrderByApplicationNumber(
                    Long organizationId, Long placementDriveId,
                    EligibilityStatus eligibilityStatus);

    long countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(
            Long organizationId, Long placementDriveId);

    boolean existsByOrganizationIdAndPlacementDriveIdAndCandidateProfileId(
            Long organizationId, Long placementDriveId, Long candidateProfileId);

    Optional<CandidateApplication> findByOrganizationIdAndPlacementDriveIdAndApplicationNumber(
            Long organizationId, Long placementDriveId, Long applicationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from CandidateApplication application "
            + "where application.id = :applicationId "
            + "and application.organizationId = :organizationId")
    Optional<CandidateApplication> findByIdAndOrganizationIdForUpdate(
            @Param("applicationId") Long applicationId,
            @Param("organizationId") Long organizationId);
}

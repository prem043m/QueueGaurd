package com.queueguard.qms.repo;

import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateApplication;
import com.queueguard.qms.domain.EligibilityStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
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

    @Query(value = """
            select count(*) from candidate_applications
            where organization_id = :organizationId
              and (:fromAt is null or applied_at >= :fromAt)
              and (:toAt is null or applied_at < :toAt)
            """, nativeQuery = true)
    long countForAnalytics(@Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    @Query(value = """
            select application_status as status, count(*) as count
            from candidate_applications
            where organization_id = :organizationId
              and (:fromAt is null or applied_at >= :fromAt)
              and (:toAt is null or applied_at < :toAt)
            group by application_status order by application_status
            """, nativeQuery = true)
    Collection<StatusCount> countStatusesForAnalytics(
            @Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    @Query(value = """
            select count(*) from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
            """, nativeQuery = true)
    long countForDrive(@Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId);

    @Query(value = """
            select application_status as status, count(*) as count
            from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
            group by application_status order by application_status
            """, nativeQuery = true)
    Collection<StatusCount> countStatusesForDrive(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId);

    @Query(value = """
            select eligibility_status as status, count(*) as count
            from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
            group by eligibility_status order by eligibility_status
            """, nativeQuery = true)
    Collection<StatusCount> countEligibilityForDrive(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId);

    @Query(value = """
            select count(*) from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
              and application_status = 'ACCEPTED'
              and capacity_reserved_at is not null
            """, nativeQuery = true)
    long countReservedAcceptedForDrive(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId);

    @Query(value = """
            select count(*) from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
              and (:fromAt is null or applied_at >= :fromAt)
              and (:toAt is null or applied_at < :toAt)
            """, nativeQuery = true)
    long countApplicationsForFunnel(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);

    @Query(value = """
            select count(*) from candidate_applications
            where organization_id = :organizationId
              and placement_drive_id = :driveId
              and application_status = 'ACCEPTED'
              and (:fromAt is null or applied_at >= :fromAt)
              and (:toAt is null or applied_at < :toAt)
            """, nativeQuery = true)
    long countAcceptedForFunnel(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);

    @Query(value = """
            WITH selected_applications AS (
                SELECT ca.id AS application_id,
                       ca.placement_drive_id,
                       ca.application_status,
                       cp.id AS candidate_profile_id,
                       CASE
                           WHEN cp.department IS NULL OR btrim(cp.department) = ''
                               THEN 'Unspecified'
                           ELSE cp.department
                       END AS department
                FROM candidate_applications ca
                JOIN candidate_profiles cp
                  ON cp.organization_id = ca.organization_id
                 AND cp.id = ca.candidate_profile_id
                 AND cp.user_id = ca.student_user_id
                WHERE ca.organization_id = :organizationId
                  AND (CAST(:fromAt AS timestamptz) IS NULL
                       OR ca.applied_at >= CAST(:fromAt AS timestamptz))
                  AND (CAST(:toAt AS timestamptz) IS NULL
                       OR ca.applied_at < CAST(:toAt AS timestamptz))
            )
            SELECT sa.department AS "department",
                   COUNT(DISTINCT sa.candidate_profile_id) AS "totalCandidates",
                   COUNT(DISTINCT sa.application_id) AS "totalApplications",
                   COUNT(DISTINCT CASE WHEN sa.application_status = 'SUBMITTED'
                       THEN sa.application_id END) AS "submitted",
                   COUNT(DISTINCT CASE WHEN sa.application_status = 'ACCEPTED'
                       THEN sa.application_id END) AS "accepted",
                   COUNT(DISTINCT CASE WHEN sa.application_status = 'REJECTED'
                       THEN sa.application_id END) AS "rejected",
                   COUNT(DISTINCT CASE WHEN sa.application_status = 'WITHDRAWN'
                       THEN sa.application_id END) AS "withdrawn",
                   COUNT(DISTINCT CASE WHEN cr.candidate_application_id IS NOT NULL
                       THEN sa.application_id END) AS "interviewRegistered",
                   COUNT(DISTINCT CASE WHEN cr.result = 'QUALIFIED'
                       THEN sa.application_id END) AS "qualified",
                   COUNT(DISTINCT CASE WHEN cr.result = 'SELECTED'
                       THEN sa.application_id END) AS "selected"
            FROM selected_applications sa
            LEFT JOIN candidate_rounds cr
              ON cr.organization_id = :organizationId
             AND cr.candidate_application_id = sa.application_id
             AND cr.placement_drive_id = sa.placement_drive_id
            GROUP BY sa.department
            ORDER BY sa.department
            """, nativeQuery = true)
    List<DepartmentAnalyticsRow> findDepartmentAnalytics(
            @Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);
}

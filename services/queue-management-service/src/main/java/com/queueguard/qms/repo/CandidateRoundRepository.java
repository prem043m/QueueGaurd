package com.queueguard.qms.repo;

import com.queueguard.qms.domain.CandidateRound;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateRoundRepository extends JpaRepository<CandidateRound, Long> {
    Optional<CandidateRound> findByIdAndOrganizationId(Long id, Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidateRound from CandidateRound candidateRound "
            + "where candidateRound.id = :candidateRoundId "
            + "and candidateRound.organizationId = :organizationId")
    Optional<CandidateRound> findByIdAndOrganizationIdForUpdate(
            @Param("candidateRoundId") Long candidateRoundId,
            @Param("organizationId") Long organizationId);

    Optional<CandidateRound> findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(
            Long organizationId, Long candidateApplicationId, Long interviewRoundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidateRound from CandidateRound candidateRound "
            + "where candidateRound.organizationId = :organizationId "
            + "and candidateRound.candidateApplicationId = :applicationId "
            + "and candidateRound.interviewRoundId = :roundId")
    Optional<CandidateRound> findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundIdForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("applicationId") Long applicationId,
            @Param("roundId") Long roundId);

    List<CandidateRound> findByOrganizationIdAndCandidateApplicationIdOrderByInterviewRoundId(
            Long organizationId, Long candidateApplicationId);

    List<CandidateRound> findByOrganizationIdAndInterviewRoundIdOrderById(
            Long organizationId, Long interviewRoundId);

    Optional<CandidateRound> findByOrganizationIdAndQueueEntryId(
            Long organizationId, Long queueEntryId);

    boolean existsByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(
            Long organizationId, Long candidateApplicationId, Long interviewRoundId);

    @Query(value = """
            select cr.result as status, count(distinct cr.candidate_application_id) as count
            from candidate_rounds cr
            where cr.organization_id = :organizationId
              and cr.placement_drive_id = :driveId
              and cr.result is not null
            group by cr.result order by cr.result
            """, nativeQuery = true)
    Collection<StatusCount> countResultsForDrive(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId);

    @Query(value = """
            select count(distinct cr.candidate_application_id)
            from candidate_rounds cr
            join candidate_applications ca
              on ca.organization_id = cr.organization_id
             and ca.id = cr.candidate_application_id
             and ca.placement_drive_id = cr.placement_drive_id
            where cr.organization_id = :organizationId
              and cr.placement_drive_id = :driveId
              and (:fromAt is null or ca.applied_at >= :fromAt)
              and (:toAt is null or ca.applied_at < :toAt)
            """, nativeQuery = true)
    long countRegisteredForFunnel(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);

    @Query(value = """
            select count(distinct cr.candidate_application_id)
            from candidate_rounds cr
            join candidate_applications ca
              on ca.organization_id = cr.organization_id
             and ca.id = cr.candidate_application_id
             and ca.placement_drive_id = cr.placement_drive_id
            where cr.organization_id = :organizationId
              and cr.placement_drive_id = :driveId
              and cr.result = 'QUALIFIED'
              and (:fromAt is null or ca.applied_at >= :fromAt)
              and (:toAt is null or ca.applied_at < :toAt)
            """, nativeQuery = true)
    long countQualifiedForFunnel(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);

    @Query(value = """
            select count(distinct cr.candidate_application_id)
            from candidate_rounds cr
            join candidate_applications ca
              on ca.organization_id = cr.organization_id
             and ca.id = cr.candidate_application_id
             and ca.placement_drive_id = cr.placement_drive_id
            where cr.organization_id = :organizationId
              and cr.placement_drive_id = :driveId
              and cr.result = 'SELECTED'
              and (:fromAt is null or ca.applied_at >= :fromAt)
              and (:toAt is null or ca.applied_at < :toAt)
            """, nativeQuery = true)
    long countSelectedForFunnel(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);
}

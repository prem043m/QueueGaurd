package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InterviewRound;
import com.queueguard.qms.domain.InterviewRoundStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewRoundRepository extends JpaRepository<InterviewRound, Long> {
    Optional<InterviewRound> findByIdAndOrganizationId(Long id, Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select round from InterviewRound round "
            + "where round.id = :roundId and round.organizationId = :organizationId")
    Optional<InterviewRound> findByIdAndOrganizationIdForUpdate(
            @Param("roundId") Long roundId,
            @Param("organizationId") Long organizationId);

    List<InterviewRound> findByOrganizationIdAndPlacementDriveIdOrderByRoundNumber(
            Long organizationId, Long placementDriveId);

    Optional<InterviewRound> findByOrganizationIdAndPlacementDriveIdAndRoundNumber(
            Long organizationId, Long placementDriveId, Integer roundNumber);

    boolean existsByOrganizationIdAndPlacementDriveIdAndRoundNumberAndIdNot(
            Long organizationId, Long placementDriveId, Integer roundNumber, Long id);

    List<InterviewRound> findByOrganizationIdAndStatusOrderByPlacementDriveIdAscRoundNumberAsc(
            Long organizationId, InterviewRoundStatus status);

    List<InterviewRound> findByOrganizationIdAndPlacementDriveIdAndStatusOrderByRoundNumber(
            Long organizationId, Long placementDriveId, InterviewRoundStatus status);

    @Query(value = """
            select count(*) from interview_rounds
            where organization_id = :organizationId
              and (:fromAt is null or created_at >= :fromAt)
              and (:toAt is null or created_at < :toAt)
            """, nativeQuery = true)
    long countForAnalytics(@Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    @Query(value = """
            select status as status, count(*) as count from interview_rounds
            where organization_id = :organizationId
              and (:fromAt is null or created_at >= :fromAt)
              and (:toAt is null or created_at < :toAt)
            group by status order by status
            """, nativeQuery = true)
    Collection<StatusCount> countStatusesForAnalytics(
            @Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    @Query(value = """
            select r.id as "roundId",
                   r.round_number as "roundNumber",
                   r.name as "roundName",
                   r.round_type as "roundType",
                   r.status as "roundStatus",
                   count(distinct cr.candidate_application_id) as "registeredCandidates",
                   count(distinct cr.candidate_application_id) filter (
                       where cr.result = 'QUALIFIED') as "qualifiedCandidates",
                   count(distinct cr.candidate_application_id) filter (
                       where cr.result = 'DISQUALIFIED') as "disqualifiedCandidates",
                   count(distinct cr.candidate_application_id) filter (
                       where cr.result = 'SELECTED') as "selectedCandidates",
                   count(distinct cr.candidate_application_id) filter (
                       where cr.result = 'HOLD') as "holdCandidates",
                   count(distinct cr.candidate_application_id) filter (
                       where cr.result is null) as "undecidedCandidates",
                   count(distinct ie.id) as "evaluationCount"
            from interview_rounds r
            left join candidate_rounds cr
              on cr.organization_id = r.organization_id
             and cr.interview_round_id = r.id
             and cr.placement_drive_id = r.placement_drive_id
             and (:fromAt is null or cr.created_at >= :fromAt)
             and (:toAt is null or cr.created_at < :toAt)
            left join interview_evaluations ie
              on ie.organization_id = cr.organization_id
             and ie.candidate_round_id = cr.id
            where r.organization_id = :organizationId
              and r.placement_drive_id = :driveId
            group by r.id, r.round_number, r.name, r.round_type, r.status
            order by r.round_number
            """, nativeQuery = true)
    List<RoundProgressionRow> findProgression(
            @Param("organizationId") Long organizationId,
            @Param("driveId") Long driveId,
            @Param("fromAt") Instant fromAt,
            @Param("toAt") Instant toAt);
}

package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InterviewerAssignment;
import com.queueguard.qms.domain.InterviewerAssignmentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewerAssignmentRepository
        extends JpaRepository<InterviewerAssignment, Long> {
    Optional<InterviewerAssignment> findByIdAndOrganizationId(Long id, Long organizationId);

    List<InterviewerAssignment> findByOrganizationIdAndInterviewRoundIdOrderByAssignedAt(
            Long organizationId, Long interviewRoundId);

    List<InterviewerAssignment> findByOrganizationIdAndInterviewerUserIdOrderByAssignedAtDesc(
            Long organizationId, Long interviewerUserId);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from InterviewerAssignment assignment
            where assignment.organizationId = :organizationId
              and assignment.interviewRoundId = :interviewRoundId
              and assignment.interviewerUserId = :interviewerUserId
              and assignment.status in
                  (com.queueguard.qms.domain.InterviewerAssignmentStatus.ASSIGNED,
                   com.queueguard.qms.domain.InterviewerAssignmentStatus.ACTIVE)
            """)
    boolean existsActiveAssignment(
            @Param("organizationId") Long organizationId,
            @Param("interviewRoundId") Long interviewRoundId,
            @Param("interviewerUserId") Long interviewerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assignment from InterviewerAssignment assignment "
            + "where assignment.organizationId = :organizationId "
            + "and assignment.interviewRoundId = :interviewRoundId "
            + "and assignment.interviewerUserId = :interviewerUserId "
            + "and assignment.status in "
            + "(com.queueguard.qms.domain.InterviewerAssignmentStatus.ASSIGNED, "
            + "com.queueguard.qms.domain.InterviewerAssignmentStatus.ACTIVE)")
    List<InterviewerAssignment> findActiveAssignmentsForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("interviewRoundId") Long interviewRoundId,
            @Param("interviewerUserId") Long interviewerUserId);

    Optional<InterviewerAssignment>
            findByOrganizationIdAndInterviewRoundIdAndInterviewerUserIdAndPanelName(
                    Long organizationId, Long interviewRoundId,
                    Long interviewerUserId, String panelName);

    @Query(value = """
                    select ia.interviewer_user_id as "interviewerUserId",
                           count(distinct ia.id) as "activeAssignmentCount",
                           count(distinct cr.id) as "assignedCandidateCount",
                           count(distinct cr.id) filter (
                               where ie.id is not null) as "completedEvaluationCount",
                           count(distinct cr.id) filter (
                               where cr.id is not null and ie.id is null) as "pendingEvaluationCount"
                    from interviewer_assignments ia
                    join interview_rounds r
                      on r.organization_id = ia.organization_id
                     and r.id = ia.interview_round_id
                    left join candidate_rounds cr
                      on cr.organization_id = ia.organization_id
                     and cr.interview_round_id = ia.interview_round_id
                     and cr.placement_drive_id = r.placement_drive_id
                    left join interview_evaluations ie
                      on ie.organization_id = cr.organization_id
                     and ie.candidate_round_id = cr.id
                     and ie.interviewer_user_id = ia.interviewer_user_id
                    where ia.organization_id = :organizationId
                      and ia.status in ('ASSIGNED', 'ACTIVE')
                      and (:driveId is null or r.placement_drive_id = :driveId)
                      and (:roundId is null or ia.interview_round_id = :roundId)
                      and (:interviewerId is null or ia.interviewer_user_id = :interviewerId)
                    group by ia.interviewer_user_id
                    order by ia.interviewer_user_id
                    """, nativeQuery = true)
    List<InterviewerWorkloadRow> findWorkload(
                    @Param("organizationId") Long organizationId,
                    @Param("driveId") Long driveId,
                    @Param("roundId") Long roundId,
                    @Param("interviewerId") Long interviewerId);
}

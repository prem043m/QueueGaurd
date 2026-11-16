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
}

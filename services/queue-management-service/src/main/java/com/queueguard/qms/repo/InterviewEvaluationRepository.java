package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InterviewEvaluation;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewEvaluationRepository
        extends JpaRepository<InterviewEvaluation, Long> {
    List<InterviewEvaluation>
            findByOrganizationIdAndCandidateRoundIdOrderBySubmittedAtAscIdAsc(
            Long organizationId, Long candidateRoundId);

    List<InterviewEvaluation>
            findByOrganizationIdAndInterviewerUserIdOrderBySubmittedAtDescIdDesc(
            Long organizationId, Long interviewerUserId);

    boolean existsByOrganizationIdAndCandidateRoundIdAndInterviewerUserId(
            Long organizationId, Long candidateRoundId, Long interviewerUserId);

    @Query(value = """
            select count(*) from interview_evaluations
            where organization_id = :organizationId
              and (:fromAt is null or submitted_at >= :fromAt)
              and (:toAt is null or submitted_at < :toAt)
            """, nativeQuery = true)
    long countForAnalytics(@Param("organizationId") Long organizationId,
            @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);
}

package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InterviewEvaluation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

package com.queueguard.qms.repo;

import com.queueguard.qms.domain.CandidateRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRoundRepository extends JpaRepository<CandidateRound, Long> {
    Optional<CandidateRound> findByIdAndOrganizationId(Long id, Long organizationId);

    List<CandidateRound> findByOrganizationIdAndCandidateApplicationIdOrderByInterviewRoundId(
            Long organizationId, Long candidateApplicationId);

    List<CandidateRound> findByOrganizationIdAndInterviewRoundIdOrderById(
            Long organizationId, Long interviewRoundId);

    Optional<CandidateRound> findByOrganizationIdAndQueueEntryId(
            Long organizationId, Long queueEntryId);

    boolean existsByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(
            Long organizationId, Long candidateApplicationId, Long interviewRoundId);
}

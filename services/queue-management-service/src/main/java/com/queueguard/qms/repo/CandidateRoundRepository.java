package com.queueguard.qms.repo;

import com.queueguard.qms.domain.CandidateRound;
import jakarta.persistence.LockModeType;
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
}

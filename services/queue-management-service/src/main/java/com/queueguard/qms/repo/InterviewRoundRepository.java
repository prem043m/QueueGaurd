package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InterviewRound;
import com.queueguard.qms.domain.InterviewRoundStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
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
}

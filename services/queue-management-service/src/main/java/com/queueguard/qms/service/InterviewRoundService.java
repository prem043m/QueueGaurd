package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.InterviewRound;
import com.queueguard.qms.domain.InterviewRoundStatus;
import com.queueguard.qms.domain.InterviewRoundType;
import com.queueguard.qms.domain.Membership;
import com.queueguard.qms.domain.OrganizationRole;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.domain.QueueConfiguration;
import com.queueguard.qms.domain.QueueStatus;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import com.queueguard.qms.repo.QueueConfigurationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewRoundService {
    private final InterviewRoundRepository roundRepository;
    private final PlacementDriveRepository driveRepository;
    private final QueueConfigurationRepository queueRepository;
    private final InterviewerAssignmentRepository assignmentRepository;
    private final Phase3Access access;

    public InterviewRoundService(
            InterviewRoundRepository roundRepository,
            PlacementDriveRepository driveRepository,
            QueueConfigurationRepository queueRepository,
            InterviewerAssignmentRepository assignmentRepository,
            Phase3Access access) {
        this.roundRepository = roundRepository;
        this.driveRepository = driveRepository;
        this.queueRepository = queueRepository;
        this.assignmentRepository = assignmentRepository;
        this.access = access;
    }

    @Transactional
    public InterviewRound create(
            Long actorId, Long organizationId, Long driveId, Long queueConfigurationId,
            int roundNumber, String name, String description, InterviewRoundType roundType,
            Instant scheduledStartAt, Instant scheduledEndAt, String qualificationCriteria) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = driveRepository.findByIdAndOrganizationIdForUpdate(
                driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
        if (drive.getStatus() == PlacementDriveStatus.DRAFT
                || drive.getStatus() == PlacementDriveStatus.CANCELLED
                || drive.getStatus() == PlacementDriveStatus.COMPLETED) {
            throw conflict("DRIVE_NOT_AVAILABLE_FOR_ROUNDS",
                    "Rounds cannot be created for a draft, completed, or cancelled drive");
        }
        queueRepository.findByOrganizationAndId(organizationId, queueConfigurationId)
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        validateRound(roundNumber, name, roundType, scheduledStartAt,
                scheduledEndAt, qualificationCriteria);
        if (roundRepository.findByOrganizationIdAndPlacementDriveIdAndRoundNumber(
                organizationId, driveId, roundNumber).isPresent()) {
            throw conflict("ROUND_NUMBER_EXISTS", "Round number already exists for this drive");
        }

        InterviewRound round = new InterviewRound();
        round.setOrganizationId(organizationId);
        round.setPlacementDriveId(driveId);
        round.setQueueConfigurationId(queueConfigurationId);
        round.setCreatedBy(actorId);
        round.setRoundNumber(roundNumber);
        round.setName(name.trim());
        round.setDescription(trimToNull(description));
        round.setRoundType(roundType);
        round.setScheduledStartAt(scheduledStartAt);
        round.setScheduledEndAt(scheduledEndAt);
        round.setQualificationCriteria(qualificationCriteria.trim());
        round.setStatus(InterviewRoundStatus.DRAFT);
        return roundRepository.save(round);
    }

    @Transactional(readOnly = true)
    public InterviewRound get(Long actorId, Long organizationId, Long roundId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        if (!isManager(actor)
                && !assignmentRepository.existsActiveAssignment(
                        organizationId, roundId, actorId)) {
            throw new com.queueguard.common.exception.ForbiddenException(
                    "Only drive managers and assigned interviewers may view this round");
        }
        return round;
    }

    @Transactional(readOnly = true)
    public List<InterviewRound> listByDrive(Long actorId, Long organizationId, Long driveId) {
        access.requireActiveMember(actorId, organizationId);
        if (driveRepository.findByIdAndOrganizationId(driveId, organizationId).isEmpty()) {
            throw new NotFoundException("Placement drive not found");
        }
        return roundRepository.findByOrganizationIdAndPlacementDriveIdOrderByRoundNumber(
                organizationId, driveId);
    }

    @Transactional(readOnly = true)
    public List<InterviewRound> listByStatus(
            Long actorId, Long organizationId, InterviewRoundStatus status) {
        access.requireDriveManager(actorId, organizationId);
        return roundRepository
                .findByOrganizationIdAndStatusOrderByPlacementDriveIdAscRoundNumberAsc(
                        organizationId, status);
    }

    @Transactional
    public InterviewRound updateDetails(
            Long actorId, Long organizationId, Long roundId,
            String name, String description, InterviewRoundType type,
            Instant scheduledStartAt, Instant scheduledEndAt, String qualificationCriteria) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        if (round.getStatus() != InterviewRoundStatus.DRAFT) {
            throw conflict("ROUND_DETAILS_LOCKED", "Only a draft round can be edited");
        }
        validateRound(round.getRoundNumber(), name, type, scheduledStartAt,
                scheduledEndAt, qualificationCriteria);
        round.setName(name.trim());
        round.setDescription(trimToNull(description));
        round.setRoundType(type);
        round.setScheduledStartAt(scheduledStartAt);
        round.setScheduledEndAt(scheduledEndAt);
        round.setQualificationCriteria(qualificationCriteria.trim());
        return roundRepository.save(round);
    }

    @Transactional
    public InterviewRound open(Long actorId, Long organizationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        if (round.getStatus() != InterviewRoundStatus.DRAFT
                && round.getStatus() != InterviewRoundStatus.PAUSED) {
            throw conflict("INVALID_ROUND_TRANSITION", "Only draft or paused rounds can be opened");
        }
        validateReadiness(round, organizationId);
        PlacementDrive drive = driveRepository.findByIdAndOrganizationIdForUpdate(
                round.getPlacementDriveId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
        if (drive.getStatus() != PlacementDriveStatus.PUBLISHED
                && drive.getStatus() != PlacementDriveStatus.REGISTRATION_OPEN
                && drive.getStatus() != PlacementDriveStatus.REGISTRATION_CLOSED
                && drive.getStatus() != PlacementDriveStatus.STARTED) {
            throw conflict("DRIVE_NOT_AVAILABLE_FOR_ROUNDS",
                    "The drive is not in a state that permits opening a round");
        }
        round.setStatus(InterviewRoundStatus.OPEN);
        return roundRepository.save(round);
    }

    @Transactional
    public InterviewRound pause(Long actorId, Long organizationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        requireStatus(round, InterviewRoundStatus.OPEN, "Only an open round can be paused");
        round.setStatus(InterviewRoundStatus.PAUSED);
        return roundRepository.save(round);
    }

    @Transactional
    public InterviewRound close(Long actorId, Long organizationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        if (round.getStatus() != InterviewRoundStatus.OPEN
                && round.getStatus() != InterviewRoundStatus.PAUSED) {
            throw conflict("INVALID_ROUND_TRANSITION", "Only open or paused rounds can be closed");
        }
        round.setStatus(InterviewRoundStatus.CLOSED);
        return roundRepository.save(round);
    }

    private InterviewRound requireRound(Long roundId, Long organizationId) {
        return roundRepository.findByIdAndOrganizationId(roundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interview round not found"));
    }

    private void validateReadiness(InterviewRound round, Long organizationId) {
        validateRound(round.getRoundNumber(), round.getName(), round.getRoundType(),
                round.getScheduledStartAt(), round.getScheduledEndAt(),
                round.getQualificationCriteria());
        if (roundRepository.existsByOrganizationIdAndPlacementDriveIdAndRoundNumberAndIdNot(
                organizationId, round.getPlacementDriveId(),
                round.getRoundNumber(), round.getId())) {
            throw conflict("ROUND_NUMBER_EXISTS",
                    "Round number already exists for this drive");
        }

        QueueConfiguration queue = queueRepository
                .findByOrganizationAndId(organizationId, round.getQueueConfigurationId())
                .orElseThrow(() -> new NotFoundException("Queue configuration not found"));
        if (queue.getName() == null || queue.getName().isBlank()
                || queue.getQueueCode() == null || queue.getQueueCode().isBlank()
                || queue.getQueueType() == null || queue.getOrderingPolicy() == null
                || queue.getStatus() == QueueStatus.ARCHIVED) {
            throw conflict("QUEUE_CONFIGURATION_NOT_READY",
                    "Queue configuration is incomplete or cannot be opened");
        }
    }

    private static void validateRound(
            int roundNumber, String name, InterviewRoundType type,
            Instant start, Instant end, String criteria) {
        if (roundNumber <= 0) {
            throw conflict("INVALID_ROUND_NUMBER", "Round number must be positive");
        }
        if (name == null || name.isBlank()) {
            throw conflict("INVALID_ROUND_NAME", "Round name is required");
        }
        if (type == null) {
            throw conflict("INVALID_ROUND_TYPE", "Round type is required");
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw conflict("INVALID_INTERVIEW_SCHEDULE",
                    "Interview end time must be later than start time");
        }
        if (criteria == null || criteria.isBlank()) {
            throw conflict("INVALID_QUALIFICATION_CRITERIA",
                    "Qualification criteria are required");
        }
    }

    private static void requireStatus(
            InterviewRound round, InterviewRoundStatus expected, String message) {
        if (round.getStatus() != expected) {
            throw conflict("INVALID_ROUND_TRANSITION", message);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    private static boolean isManager(Membership membership) {
        return membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }
}

package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.Phase3Dtos.CandidateRoundView;
import com.queueguard.qms.dto.QueueDtos.CreateQueueEntryRequest;
import com.queueguard.qms.dto.QueueDtos.QueueEntryView;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.CandidateProfileRepository;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import com.queueguard.qms.repo.QueueEntryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateRoundService {
    private final CandidateRoundRepository candidateRoundRepository;
    private final CandidateApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final InterviewRoundRepository roundRepository;
    private final PlacementDriveRepository driveRepository;
    private final InterviewerAssignmentRepository assignmentRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;
    private final Phase3Access access;

    public CandidateRoundService(
            CandidateRoundRepository candidateRoundRepository,
            CandidateApplicationRepository applicationRepository,
            CandidateProfileRepository profileRepository,
            InterviewRoundRepository roundRepository,
            PlacementDriveRepository driveRepository,
            InterviewerAssignmentRepository assignmentRepository,
            QueueEntryRepository queueEntryRepository,
            QueueService queueService,
            Phase3Access access) {
        this.candidateRoundRepository = candidateRoundRepository;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.roundRepository = roundRepository;
        this.driveRepository = driveRepository;
        this.assignmentRepository = assignmentRepository;
        this.queueEntryRepository = queueEntryRepository;
        this.queueService = queueService;
        this.access = access;
    }

    @Transactional
    public CandidateRoundView register(
            Long actorId, Long organizationId, Long applicationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        InterviewRound round = requireRound(roundId, organizationId);
        CandidateApplication application = requireApplication(applicationId, organizationId);
        validateSameDrive(application, round);
        lockDrive(round.getPlacementDriveId(), organizationId);
        return registerForRound(actorId, organizationId, applicationId, roundId);
    }

    @Transactional(readOnly = true)
    public List<CandidateRoundView> listForRound(
            Long actorId, Long organizationId, Long roundId) {
        requireRoundViewer(actorId, organizationId, roundId);
        return candidateRoundRepository
                .findByOrganizationIdAndInterviewRoundIdOrderById(organizationId, roundId)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<CandidateRoundView> listForApplication(
            Long actorId, Long organizationId, Long applicationId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateApplication application = requireApplication(applicationId, organizationId);
        if (!isManager(actor) && !actorId.equals(application.getStudentUserId())) {
            throw new ForbiddenException("Candidates may view only their own round progression");
        }
        return candidateRoundRepository
                .findByOrganizationIdAndCandidateApplicationIdOrderByInterviewRoundId(
                        organizationId, applicationId)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CandidateRoundView get(
            Long actorId, Long organizationId, Long applicationId, Long roundId) {
        CandidateRound candidateRound = requireCandidateRound(
                applicationId, roundId, organizationId);
        Membership actor = access.requireActiveMember(actorId, organizationId);
        CandidateApplication application = requireApplication(applicationId, organizationId);
        if (!isManager(actor)
                && !actorId.equals(application.getStudentUserId())
                && !isAssignedInterviewer(actor, actorId, organizationId, roundId)) {
            throw new ForbiddenException("Candidate round is not available to this user");
        }
        return view(candidateRound);
    }

    @Transactional
    public CandidateRoundView decide(
            Long actorId, Long organizationId, Long applicationId, Long roundId,
            CandidateRoundResult result, String reason) {
        requireDecisionAccess(actorId, organizationId, roundId, result);
        CandidateRound candidateRound = lockCandidateRound(
                applicationId, roundId, organizationId);
        QueueEntry queueEntry = queueEntryRepository
                .findByIdAndOrganizationIdForUpdate(
                        candidateRound.getQueueEntryId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        if (!queueEntry.getQueueConfigurationId().equals(
                candidateRound.getQueueConfigurationId())) {
            throw new NotFoundException("Queue entry not found");
        }
        if (queueEntry.getStatus() != QueueEntryStatus.COMPLETED) {
            throw conflict("INTERVIEW_NOT_COMPLETED",
                    "An outcome can be recorded only after the queue entry is completed");
        }
        if (candidateRound.getResult() != null) {
            if (candidateRound.getResult() == result) {
                return view(candidateRound, queueEntry);
            }
            throw conflict("CANDIDATE_ROUND_ALREADY_DECIDED",
                    "A candidate-round decision cannot be revised");
        }

        candidateRound.setResult(result);
        candidateRound.setDecisionReason(trimToNull(reason));
        candidateRound.setDecidedBy(actorId);
        candidateRound.setDecidedAt(Instant.now());
        candidateRound = candidateRoundRepository.saveAndFlush(candidateRound);
        return view(candidateRound, queueEntry);
    }

    @Transactional
    public CandidateRoundView progress(
            Long actorId, Long organizationId, Long applicationId, Long roundId) {
        access.requireDriveManager(actorId, organizationId);
        CandidateRound sourceReference = requireCandidateRound(
                applicationId, roundId, organizationId);
        InterviewRound sourceRound = roundRepository
                .findByIdAndOrganizationId(sourceReference.getInterviewRoundId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Interview round not found"));
        lockDrive(sourceRound.getPlacementDriveId(), organizationId);
        CandidateRound source = lockCandidateRound(applicationId, roundId, organizationId);
        if (source.getResult() != CandidateRoundResult.QUALIFIED) {
            throw conflict("CANDIDATE_NOT_QUALIFIED",
                    "Only a qualified candidate can progress to another round");
        }
        InterviewRound nextRound = roundRepository
                .findByOrganizationIdAndPlacementDriveIdOrderByRoundNumber(
                        organizationId, sourceRound.getPlacementDriveId())
                .stream()
                .filter(round -> round.getRoundNumber() > sourceRound.getRoundNumber())
                .findFirst()
                .orElseThrow(() -> conflict("NEXT_ROUND_NOT_FOUND",
                        "There is no later interview round for this drive"));
        return registerForRound(actorId, organizationId, applicationId, nextRound.getId());
    }

    private CandidateRoundView registerForRound(
            Long actorId, Long organizationId, Long applicationId, Long roundId) {
        CandidateApplication applicationReference = requireApplication(
                applicationId, organizationId);
        InterviewRound roundReference = requireRound(roundId, organizationId);
        validateSameDrive(applicationReference, roundReference);

        List<InterviewRound> orderedRounds = roundRepository
                .findByOrganizationIdAndPlacementDriveIdOrderByRoundNumber(
                        organizationId, roundReference.getPlacementDriveId());
        int roundIndex = -1;
        for (int index = 0; index < orderedRounds.size(); index++) {
            if (orderedRounds.get(index).getId().equals(roundId)) {
                roundIndex = index;
                break;
            }
        }
        if (roundIndex < 0) {
            throw new NotFoundException("Interview round not found");
        }
        if (roundIndex > 0) {
            InterviewRound previousRound = orderedRounds.get(roundIndex - 1);
            CandidateRound previousCandidateRound = candidateRoundRepository
                    .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundIdForUpdate(
                            organizationId, applicationId, previousRound.getId())
                    .orElseThrow(() -> previousRoundNotQualified());
            if (!previousRound.getPlacementDriveId().equals(
                    applicationReference.getPlacementDriveId())
                    || !previousCandidateRound.getPlacementDriveId().equals(
                            previousRound.getPlacementDriveId())
                    || previousCandidateRound.getResult() != CandidateRoundResult.QUALIFIED) {
                throw previousRoundNotQualified();
            }
        }

        InterviewRound round = lockRound(roundId, organizationId);
        CandidateApplication application = lockApplication(applicationId, organizationId);
        validateSameDrive(application, round);

        var existing = candidateRoundRepository
                .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(
                        organizationId, applicationId, roundId);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        if (round.getStatus() != InterviewRoundStatus.OPEN) {
            throw conflict("ROUND_NOT_OPEN", "Candidates may be registered only in an open round");
        }
        if (application.getApplicationStatus() != ApplicationStatus.ACCEPTED
                || application.getCapacityReservedAt() == null) {
            throw conflict("APPLICATION_NOT_ACCEPTED",
                    "Only accepted applications may be registered for an interview round");
        }
        if (application.getEligibilityStatus() != EligibilityStatus.ELIGIBLE) {
            throw conflict("APPLICATION_NOT_ELIGIBLE",
                    "Only eligible candidates may be registered for an interview round");
        }

        CandidateProfile profile = profileRepository
                .findByIdAndOrganizationId(application.getCandidateProfileId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate profile not found"));
        if (!profile.getUserId().equals(application.getStudentUserId())) {
            throw new ForbiddenException("Application does not belong to its candidate profile");
        }
        QueueEntryView queueEntry = queueService.registerParticipant(actorId, organizationId,
                round.getQueueConfigurationId(),
                new CreateQueueEntryRequest("application:" + application.getId(),
                        application.getStudentUserId(), profile.getStudentName(),
                        profile.getEmail(), null));

        CandidateRound candidateRound = new CandidateRound();
        candidateRound.setOrganizationId(organizationId);
        candidateRound.setCandidateApplicationId(applicationId);
        candidateRound.setInterviewRoundId(round.getId());
        candidateRound.setPlacementDriveId(round.getPlacementDriveId());
        candidateRound.setQueueConfigurationId(round.getQueueConfigurationId());
        candidateRound.setQueueEntryId(queueEntry.id());
        candidateRound = candidateRoundRepository.saveAndFlush(candidateRound);
        return CandidateRoundView.from(candidateRound,
                queueEntryRepository.findByIdAndOrganizationId(queueEntry.id(), organizationId)
                        .orElseThrow(() -> new NotFoundException("Queue entry not found")));
    }

    private CandidateRoundView view(CandidateRound candidateRound) {
        QueueEntry queueEntry = queueEntryRepository
                .findByIdAndOrganizationId(candidateRound.getQueueEntryId(),
                        candidateRound.getOrganizationId())
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        return view(candidateRound, queueEntry);
    }

    private static CandidateRoundView view(
            CandidateRound candidateRound, QueueEntry queueEntry) {
        return CandidateRoundView.from(candidateRound, queueEntry);
    }

    private void requireDecisionAccess(
            Long actorId, Long organizationId, Long roundId, CandidateRoundResult result) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        if (isManager(actor)) {
            return;
        }
        if (result != CandidateRoundResult.SELECTED
                && isAssignedInterviewer(actor, actorId, organizationId, roundId)) {
            return;
        }
        throw new ForbiddenException("Drive manager or assigned interviewer permission required");
    }

    private void requireRoundViewer(Long actorId, Long organizationId, Long roundId) {
        Membership actor = access.requireActiveMember(actorId, organizationId);
        if (isManager(actor)) {
            if (roundRepository.findByIdAndOrganizationId(roundId, organizationId).isEmpty()) {
                throw new NotFoundException("Interview round not found");
            }
            return;
        }
        if (!isAssignedInterviewer(actor, actorId, organizationId, roundId)) {
            throw new ForbiddenException("Drive manager or assigned interviewer permission required");
        }
    }

    private boolean isAssignedInterviewer(
            Membership actor, Long actorId, Long organizationId, Long roundId) {
        return actor.getRoles().contains(OrganizationRole.INTERVIEWER)
                && assignmentRepository.existsActiveAssignment(
                        organizationId, roundId, actorId);
    }

    private CandidateRound lockCandidateRound(
            Long applicationId, Long roundId, Long organizationId) {
        return candidateRoundRepository
                .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundIdForUpdate(
                        organizationId, applicationId, roundId)
                .orElseThrow(() -> new NotFoundException("Candidate round not found"));
    }

    private CandidateRound requireCandidateRound(
            Long applicationId, Long roundId, Long organizationId) {
        return candidateRoundRepository
                .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(
                        organizationId, applicationId, roundId)
                .orElseThrow(() -> new NotFoundException("Candidate round not found"));
    }

    private InterviewRound lockRound(Long roundId, Long organizationId) {
        return roundRepository.findByIdAndOrganizationIdForUpdate(roundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interview round not found"));
    }

    private InterviewRound requireRound(Long roundId, Long organizationId) {
        return roundRepository.findByIdAndOrganizationId(roundId, organizationId)
                .orElseThrow(() -> new NotFoundException("Interview round not found"));
    }

    private void lockDrive(Long driveId, Long organizationId) {
        driveRepository.findByIdAndOrganizationIdForUpdate(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
    }

    private CandidateApplication lockApplication(Long applicationId, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationIdForUpdate(
                        applicationId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate application not found"));
    }

    private CandidateApplication requireApplication(Long applicationId, Long organizationId) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .orElseThrow(() -> new NotFoundException("Candidate application not found"));
    }

    private static void validateSameDrive(
            CandidateApplication application, InterviewRound round) {
        if (!application.getPlacementDriveId().equals(round.getPlacementDriveId())) {
            throw new NotFoundException("Candidate application not found");
        }
    }

    private static boolean isManager(Membership membership) {
        return membership.getRoles().contains(OrganizationRole.ORG_ADMIN)
                || membership.getRoles().contains(OrganizationRole.QUEUE_MANAGER);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    private static BusinessException previousRoundNotQualified() {
        return conflict("PREVIOUS_ROUND_NOT_QUALIFIED",
                "The candidate must qualify in the immediately preceding interview round");
    }
}

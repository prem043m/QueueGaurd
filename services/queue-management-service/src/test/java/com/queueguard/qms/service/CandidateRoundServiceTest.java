package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.QueueDtos.QueueEntryView;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.CandidateProfileRepository;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import com.queueguard.qms.repo.QueueEntryRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CandidateRoundServiceTest {
    private CandidateRoundRepository candidateRounds;
    private CandidateApplicationRepository applications;
    private CandidateProfileRepository profiles;
    private InterviewRoundRepository rounds;
    private PlacementDriveRepository drives;
    private InterviewerAssignmentRepository assignments;
    private QueueEntryRepository queueEntries;
    private QueueService queueService;
    private Phase3Access access;
    private CandidateRoundService service;

    @BeforeEach
    void setUp() {
        candidateRounds = mock(CandidateRoundRepository.class);
        applications = mock(CandidateApplicationRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        rounds = mock(InterviewRoundRepository.class);
        drives = mock(PlacementDriveRepository.class);
        assignments = mock(InterviewerAssignmentRepository.class);
        queueEntries = mock(QueueEntryRepository.class);
        queueService = mock(QueueService.class);
        access = mock(Phase3Access.class);
        service = new CandidateRoundService(candidateRounds, applications, profiles, rounds,
                drives, assignments, queueEntries, queueService, access);
    }

    @Test
    void registrationUsesQueueServiceForAcceptedEligibleApplications() {
        InterviewRound round = round(31L, InterviewRoundStatus.OPEN);
        CandidateApplication application = application(
                ApplicationStatus.ACCEPTED, EligibilityStatus.ELIGIBLE);
        application.setCapacityReservedAt(Instant.now());
        CandidateProfile profile = new CandidateProfile();
        profile.setId(9L);
        profile.setUserId(7L);
        profile.setStudentName("Candidate");
        profile.setEmail("candidate@example.test");
        QueueEntryView queueView = QueueEntryView.from(queueEntry(44L, QueueEntryStatus.WAITING));

        stubRoundAccess(round, application);
        when(candidateRounds
                .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(3L, 20L, 31L))
                .thenReturn(Optional.empty());
        when(profiles.findByIdAndOrganizationId(9L, 3L)).thenReturn(Optional.of(profile));
        when(queueService.registerParticipant(eq(5L), eq(3L), eq(12L), any()))
                .thenReturn(queueView);
        when(candidateRounds.saveAndFlush(any(CandidateRound.class)))
                .thenAnswer(invocation -> {
                    CandidateRound candidateRound = invocation.getArgument(0);
                    candidateRound.setId(50L);
                    return candidateRound;
                });
        when(queueEntries.findByIdAndOrganizationId(44L, 3L))
                .thenReturn(Optional.of(queueEntry(44L, QueueEntryStatus.WAITING)));

        var result = service.register(5L, 3L, 20L, 31L);

        assertEquals(50L, result.id());
        assertEquals(44L, result.queueEntryId());
        assertEquals(QueueEntryStatus.WAITING, result.queueEntry().status());
        verify(queueService).registerParticipant(eq(5L), eq(3L), eq(12L), any());
    }

    @Test
    void registrationRejectsUnacceptedApplicationBeforeCreatingQueueEntry() {
        InterviewRound round = round(31L, InterviewRoundStatus.OPEN);
        CandidateApplication application = application(
                ApplicationStatus.SUBMITTED, EligibilityStatus.ELIGIBLE);
        stubRoundAccess(round, application);
        when(candidateRounds
                .findByOrganizationIdAndCandidateApplicationIdAndInterviewRoundId(3L, 20L, 31L))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.register(5L, 3L, 20L, 31L));

        assertEquals("APPLICATION_NOT_ACCEPTED", exception.getCode());
        verifyNoInteractions(queueService);
    }

    @Test
    void unauthorizedInterviewerSelectIsRejectedBeforeCandidateRoundLock() {
        Membership interviewer = new Membership();
        interviewer.setRoles(java.util.Set.of(OrganizationRole.INTERVIEWER));
        when(access.requireActiveMember(6L, 3L)).thenReturn(interviewer);
        when(assignments.existsActiveAssignment(3L, 31L, 6L)).thenReturn(true);

        assertThrows(ForbiddenException.class,
                () -> service.decide(6L, 3L, 20L, 31L,
                        CandidateRoundResult.SELECTED, "selected"));

        verifyNoInteractions(candidateRounds, queueEntries);
    }

    private void stubRoundAccess(InterviewRound round, CandidateApplication application) {
        PlacementDrive drive = new PlacementDrive();
        drive.setId(round.getPlacementDriveId());
        drive.setOrganizationId(3L);
        when(rounds.findByIdAndOrganizationId(round.getId(), 3L))
                .thenReturn(Optional.of(round));
        when(rounds.findByOrganizationIdAndPlacementDriveIdOrderByRoundNumber(3L, 11L))
                .thenReturn(java.util.List.of(round));
        when(applications.findByIdAndOrganizationId(20L, 3L))
                .thenReturn(Optional.of(application));
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L))
                .thenReturn(Optional.of(drive));
        when(rounds.findByIdAndOrganizationIdForUpdate(round.getId(), 3L))
                .thenReturn(Optional.of(round));
        when(applications.findByIdAndOrganizationIdForUpdate(20L, 3L))
                .thenReturn(Optional.of(application));
    }

    private static InterviewRound round(long id, InterviewRoundStatus status) {
        InterviewRound round = new InterviewRound();
        round.setId(id);
        round.setOrganizationId(3L);
        round.setPlacementDriveId(11L);
        round.setQueueConfigurationId(12L);
        round.setStatus(status);
        return round;
    }

    private static CandidateApplication application(
            ApplicationStatus status, EligibilityStatus eligibility) {
        CandidateApplication application = new CandidateApplication();
        application.setId(20L);
        application.setOrganizationId(3L);
        application.setPlacementDriveId(11L);
        application.setCandidateProfileId(9L);
        application.setStudentUserId(7L);
        application.setApplicationStatus(status);
        application.setEligibilityStatus(eligibility);
        return application;
    }

    private static QueueEntry queueEntry(long id, QueueEntryStatus status) {
        QueueEntry entry = new QueueEntry();
        entry.setId(id);
        entry.setOrganizationId(3L);
        entry.setQueueConfigurationId(12L);
        entry.setTokenNumber("Q-1");
        entry.setParticipantName("Candidate");
        entry.setStatus(status);
        entry.setJoinedAt(Instant.now());
        return entry;
    }
}

package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Phase3BusinessServiceTest {
    private Phase3Access access;
    private PlacementDriveRepository drives;
    private CandidateApplicationRepository applications;
    private CandidateProfileRepository profiles;
    private InterviewRoundRepository rounds;
    private InterviewerAssignmentRepository assignments;
    private QueueConfigurationRepository queues;

    @BeforeEach
    void setUp() {
        access = mock(Phase3Access.class);
        drives = mock(PlacementDriveRepository.class);
        applications = mock(CandidateApplicationRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        rounds = mock(InterviewRoundRepository.class);
        assignments = mock(InterviewerAssignmentRepository.class);
        queues = mock(QueueConfigurationRepository.class);
    }

    @Test
    void driveCreationValidatesCapacityAndPersistsTenantAndCreator() {
        PlacementDriveService service = new PlacementDriveService(drives, applications, access);
        when(drives.save(any(PlacementDrive.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant opens = Instant.now().plusSeconds(60);
        Instant closes = opens.plusSeconds(3600);

        PlacementDrive result = service.create(7L, 3L, "  Campus  ", "desc",
                10, opens, closes);

        assertEquals(3L, result.getOrganizationId());
        assertEquals(7L, result.getCreatedBy());
        assertEquals("Campus", result.getName());
        assertEquals(PlacementDriveStatus.DRAFT, result.getStatus());
        verify(access).requireDriveManager(7L, 3L);
        assertThrows(BusinessException.class,
                () -> service.create(7L, 3L, "Campus", null, 0, null, null));
        assertThrows(BusinessException.class,
                () -> service.create(7L, 3L, "Campus", null, 2, closes, opens));
    }

    @Test
    void driveCapacityReductionUsesRowLockAndReservedTimestampCount() {
        PlacementDriveService service = new PlacementDriveService(drives, applications, access);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.PUBLISHED, 5);
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L)).thenReturn(Optional.of(drive));
        when(applications
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(3L, 11L))
                .thenReturn(4L);
        when(drives.save(drive)).thenReturn(drive);

        assertThrows(BusinessException.class, () -> service.updateCapacity(7L, 3L, 11L, 3));
        assertEquals(5, service.updateCapacity(7L, 3L, 11L, 5).getRegistrationCapacity());
        verify(drives, times(2)).findByIdAndOrganizationIdForUpdate(11L, 3L);
    }

    @Test
    void driveStatusTransitionsRejectOpeningAnUnpublishedDrive() {
        PlacementDriveService service = new PlacementDriveService(drives, applications, access);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.DRAFT, 5);
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L)).thenReturn(Optional.of(drive));
        assertThrows(BusinessException.class, () -> service.openRegistration(7L, 3L, 11L));
    }

    @Test
    void profileCreationUsesActorIdentityAndValidatesCgpa() {
        CandidateProfileService service = new CandidateProfileService(profiles, access);
        when(profiles.existsByUserIdAndOrganizationId(7L, 3L)).thenReturn(false);
        when(profiles.save(any(CandidateProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CandidateProfile profile = service.create(7L, 3L, "Student", "student@example.test",
                null, null, null, null, new BigDecimal("9.50"), "resume://id", null);

        assertEquals(7L, profile.getUserId());
        assertEquals(3L, profile.getOrganizationId());
        assertEquals(new BigDecimal("9.50"), profile.getCgpa());
        assertThrows(BusinessException.class, () -> service.create(7L, 3L, "Student",
                "student@example.test", null, null, null, null,
                new BigDecimal("10.01"), null, null));
    }

    @Test
    void profileReadAndUpdateDoNotAllowAnotherMemberToAccessIt() {
        CandidateProfileService service = new CandidateProfileService(profiles, access);
        Membership actor = membership(8L, OrganizationRole.MEMBER);
        CandidateProfile profile = profile(7L);
        when(access.requireActiveMember(8L, 3L)).thenReturn(actor);
        when(profiles.findByIdAndOrganizationId(9L, 3L)).thenReturn(Optional.of(profile));

        assertThrows(ForbiddenException.class, () -> service.get(8L, 3L, 9L));
        assertThrows(ForbiddenException.class, () -> service.update(8L, 3L, 9L,
                "Updated", "new@example.test", null, null, null, null,
                null, null, null));
    }

    @Test
    void applicationSubmissionUsesAllocatorAndRejectsDuplicate() {
        CandidateApplicationService service = new CandidateApplicationService(
                applications, profiles, drives, access);
        CandidateProfile profile = profile(7L);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.REGISTRATION_OPEN, 5);
        drive.setRegistrationOpensAt(Instant.now().minusSeconds(10));
        drive.setRegistrationClosesAt(Instant.now().plusSeconds(3600));
        when(profiles.findByIdAndOrganizationId(9L, 3L)).thenReturn(Optional.of(profile));
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L))
                .thenReturn(Optional.of(drive));
        when(applications.existsByOrganizationIdAndPlacementDriveIdAndCandidateProfileId(
                3L, 11L, 9L)).thenReturn(false);
        when(applications.allocateNextApplicationNumber(3L, 11L)).thenReturn(42L);
        when(applications.save(any(CandidateApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CandidateApplication result = service.submit(7L, 3L, 9L, 11L);
        assertEquals(42L, result.getApplicationNumber());
        assertEquals(EligibilityStatus.PENDING, result.getEligibilityStatus());
        assertEquals(ApplicationStatus.SUBMITTED, result.getApplicationStatus());
        verify(drives).findByIdAndOrganizationIdForUpdate(11L, 3L);
        verify(applications).allocateNextApplicationNumber(3L, 11L);

        when(applications.existsByOrganizationIdAndPlacementDriveIdAndCandidateProfileId(
                3L, 11L, 9L)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.submit(7L, 3L, 9L, 11L));
    }

    @Test
    void applicationAcceptanceLocksDriveAndEnforcesEligibilityAndCapacity() {
        CandidateApplicationService service = new CandidateApplicationService(
                applications, profiles, drives, access);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.REGISTRATION_CLOSED, 1);
        CandidateApplication application = application(3L, 11L, 9L, 7L,
                EligibilityStatus.ELIGIBLE, ApplicationStatus.SUBMITTED);
        when(applications.findPlacementDriveIdByIdAndOrganizationId(20L, 3L))
                .thenReturn(Optional.of(11L));
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L)).thenReturn(Optional.of(drive));
        when(applications.findByIdAndOrganizationIdForUpdate(20L, 3L))
                .thenReturn(Optional.of(application));
        when(applications
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(3L, 11L))
                .thenReturn(0L);
        when(applications.save(application)).thenReturn(application);

        CandidateApplication accepted = service.accept(5L, 3L, 20L);
        assertEquals(ApplicationStatus.ACCEPTED, accepted.getApplicationStatus());
        assertNotNull(accepted.getCapacityReservedAt());
        verify(drives).findByIdAndOrganizationIdForUpdate(11L, 3L);
    }

    @Test
    void ineligibleOrFullDriveApplicationCannotBeAccepted() {
        CandidateApplicationService service = new CandidateApplicationService(
                applications, profiles, drives, access);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.REGISTRATION_CLOSED, 1);
        CandidateApplication ineligible = application(3L, 11L, 9L, 7L,
                EligibilityStatus.INELIGIBLE, ApplicationStatus.SUBMITTED);
        when(applications.findPlacementDriveIdByIdAndOrganizationId(20L, 3L))
                .thenReturn(Optional.of(11L));
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L)).thenReturn(Optional.of(drive));
        when(applications.findByIdAndOrganizationIdForUpdate(20L, 3L))
                .thenReturn(Optional.of(ineligible));
        assertThrows(BusinessException.class, () -> service.accept(5L, 3L, 20L));

        ineligible.setEligibilityStatus(EligibilityStatus.ELIGIBLE);
        when(applications
                .countByOrganizationIdAndPlacementDriveIdAndCapacityReservedAtIsNotNull(3L, 11L))
                .thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.accept(5L, 3L, 20L));
    }

    @Test
    void withdrawalOfAcceptedApplicationRetainsReservationAndRejectionIsTerminal() {
        CandidateApplicationService service = new CandidateApplicationService(
                applications, profiles, drives, access);
        CandidateApplication application = application(3L, 11L, 9L, 7L,
                EligibilityStatus.ELIGIBLE, ApplicationStatus.ACCEPTED);
        Instant reserved = Instant.now().minusSeconds(30);
        application.setCapacityReservedAt(reserved);
        when(access.requireActiveMember(7L, 3L)).thenReturn(membership(7L, OrganizationRole.MEMBER));
        when(applications.findByIdAndOrganizationIdForUpdate(20L, 3L))
                .thenReturn(Optional.of(application));
        when(applications.save(application)).thenReturn(application);

        CandidateApplication withdrawn = service.withdraw(7L, 3L, 20L, "schedule conflict");
        assertEquals(ApplicationStatus.WITHDRAWN, withdrawn.getApplicationStatus());
        assertEquals(reserved, withdrawn.getCapacityReservedAt());
        assertEquals("schedule conflict", withdrawn.getWithdrawalReason());
        assertThrows(BusinessException.class, () -> service.reject(5L, 3L, 20L));
    }

    @Test
    void roundCreationValidatesDriveQueueScheduleAndCriteria() {
        InterviewRoundService service =
                new InterviewRoundService(rounds, drives, queues, assignments, access);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.PUBLISHED, 5);
        QueueConfiguration queue = new QueueConfiguration();
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L))
                .thenReturn(Optional.of(drive));
        when(queues.findByOrganizationAndId(3L, 12L)).thenReturn(Optional.of(queue));
        when(rounds.findByOrganizationIdAndPlacementDriveIdAndRoundNumber(3L, 11L, 1))
                .thenReturn(Optional.empty());
        when(rounds.save(any(InterviewRound.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant start = Instant.now().plusSeconds(600);
        InterviewRound round = service.create(5L, 3L, 11L, 12L, 1,
                "Technical", null, InterviewRoundType.TECHNICAL,
                start, start.plusSeconds(1800), "Pass the technical screen");
        assertEquals(InterviewRoundStatus.DRAFT, round.getStatus());
        assertEquals(12L, round.getQueueConfigurationId());
        verify(drives).findByIdAndOrganizationIdForUpdate(11L, 3L);
        assertThrows(BusinessException.class, () -> service.create(5L, 3L, 11L, 12L, 2,
                "Invalid", null, InterviewRoundType.HR, start, start, "criteria"));
    }

    @Test
    void roundReadRequiresManagerOrAssignedInterviewer() {
        InterviewRoundService service =
                new InterviewRoundService(rounds, drives, queues, assignments, access);
        InterviewRound round = new InterviewRound();
        round.setId(30L);
        when(access.requireActiveMember(7L, 3L))
                .thenReturn(membership(7L, OrganizationRole.INTERVIEWER));
        when(rounds.findByIdAndOrganizationId(30L, 3L)).thenReturn(Optional.of(round));
        when(assignments.existsActiveAssignment(3L, 30L, 7L)).thenReturn(false, true);

        assertThrows(ForbiddenException.class, () -> service.get(7L, 3L, 30L));
        assertSame(round, service.get(7L, 3L, 30L));
    }

    @Test
    void openingRoundRequiresValidReadinessAndUsableQueueConfiguration() {
        InterviewRoundService service =
                new InterviewRoundService(rounds, drives, queues, assignments, access);
        Instant start = Instant.now().plusSeconds(600);
        InterviewRound round = new InterviewRound();
        round.setId(30L);
        round.setOrganizationId(3L);
        round.setPlacementDriveId(11L);
        round.setQueueConfigurationId(12L);
        round.setRoundNumber(1);
        round.setName("Technical");
        round.setRoundType(InterviewRoundType.TECHNICAL);
        round.setScheduledStartAt(start);
        round.setScheduledEndAt(start.plusSeconds(1800));
        round.setQualificationCriteria("Pass the technical screen");
        round.setStatus(InterviewRoundStatus.DRAFT);
        PlacementDrive drive = drive(3L, 11L, PlacementDriveStatus.PUBLISHED, 5);
        QueueConfiguration queue = new QueueConfiguration();
        queue.setName("Interview queue");
        queue.setQueueCode("INTERVIEW");
        queue.setQueueType(QueueType.GENERIC);
        queue.setOrderingPolicy(OrderingPolicy.FIFO);
        queue.setStatus(QueueStatus.DRAFT);
        when(rounds.findByIdAndOrganizationId(30L, 3L)).thenReturn(Optional.of(round));
        when(rounds.findByIdAndOrganizationIdForUpdate(30L, 3L))
                .thenReturn(Optional.of(round));
        when(rounds.existsByOrganizationIdAndPlacementDriveIdAndRoundNumberAndIdNot(
                3L, 11L, 1, 30L)).thenReturn(false);
        when(queues.findByOrganizationAndId(3L, 12L)).thenReturn(Optional.of(queue));
        when(drives.findByIdAndOrganizationIdForUpdate(11L, 3L)).thenReturn(Optional.of(drive));
        when(rounds.save(round)).thenReturn(round);

        assertSame(round, service.open(5L, 3L, 30L));
        assertEquals(InterviewRoundStatus.OPEN, round.getStatus());
        verify(drives).findByIdAndOrganizationIdForUpdate(11L, 3L);

        round.setStatus(InterviewRoundStatus.DRAFT);
        queue.setStatus(QueueStatus.ARCHIVED);
        assertThrows(BusinessException.class, () -> service.open(5L, 3L, 30L));
    }

    @Test
    void assignmentListForRoundRequiresDriveManager() {
        InterviewerAssignmentService service = new InterviewerAssignmentService(
                assignments, rounds, access);
        when(rounds.findByIdAndOrganizationId(30L, 3L))
                .thenReturn(Optional.of(new InterviewRound()));

        service.listForRound(5L, 3L, 30L);

        verify(access).requireDriveManager(5L, 3L);
        verify(access, never()).requireActiveMember(5L, 3L);
    }

    @Test
    void releasedAssignmentIsReactivatedInsteadOfDuplicated() {
        InterviewerAssignmentService service = new InterviewerAssignmentService(
                assignments, rounds, access);
        InterviewRound round = new InterviewRound();
        round.setStatus(InterviewRoundStatus.OPEN);
        InterviewerAssignment existing = new InterviewerAssignment();
        existing.setStatus(InterviewerAssignmentStatus.RELEASED);
        when(rounds.findByIdAndOrganizationIdForUpdate(30L, 3L))
                .thenReturn(Optional.of(round));
        when(assignments
                .findByOrganizationIdAndInterviewRoundIdAndInterviewerUserIdAndPanelName(
                        3L, 30L, 7L, null)).thenReturn(Optional.of(existing));
        when(assignments.save(existing)).thenReturn(existing);

        InterviewerAssignment result = service.assign(5L, 3L, 30L, 7L, null, "Room A");

        assertSame(existing, result);
        assertEquals(InterviewerAssignmentStatus.ASSIGNED, result.getStatus());
        assertEquals("Room A", result.getServicePoint());
        verify(assignments, never()).save(argThat(candidate -> candidate != existing));
    }

    private static PlacementDrive drive(
            long organizationId, long driveId, PlacementDriveStatus status, int capacity) {
        PlacementDrive drive = new PlacementDrive();
        drive.setId(driveId);
        drive.setOrganizationId(organizationId);
        drive.setStatus(status);
        drive.setRegistrationCapacity(capacity);
        return drive;
    }

    private static CandidateProfile profile(long userId) {
        CandidateProfile profile = new CandidateProfile();
        profile.setId(9L);
        profile.setOrganizationId(3L);
        profile.setUserId(userId);
        return profile;
    }

    private static CandidateApplication application(
            long organizationId, long driveId, long profileId, long userId,
            EligibilityStatus eligibility, ApplicationStatus status) {
        CandidateApplication application = new CandidateApplication();
        application.setId(20L);
        application.setOrganizationId(organizationId);
        application.setPlacementDriveId(driveId);
        application.setCandidateProfileId(profileId);
        application.setStudentUserId(userId);
        application.setEligibilityStatus(eligibility);
        application.setApplicationStatus(status);
        return application;
    }

    private static Membership membership(long userId, OrganizationRole role) {
        Membership membership = new Membership();
        membership.setAuthUserId(userId);
        membership.setRoles(java.util.Set.of(role));
        return membership;
    }
}

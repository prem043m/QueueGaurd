package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateRoundResult;
import com.queueguard.qms.domain.EligibilityStatus;
import com.queueguard.qms.domain.InterviewRoundStatus;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.domain.QueueEntryStatus;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.CandidateProfileRepository;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.DepartmentAnalyticsRow;
import com.queueguard.qms.repo.InterviewEvaluationRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.InterviewerWorkloadRow;
import com.queueguard.qms.repo.RoundProgressionRow;
import com.queueguard.qms.repo.PlacementDriveRepository;
import com.queueguard.qms.repo.QueueEntryRepository;
import com.queueguard.qms.repo.StatusCount;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {
    private PlacementDriveRepository drives;
    private CandidateApplicationRepository applications;
    private CandidateProfileRepository profiles;
    private InterviewRoundRepository rounds;
    private InterviewEvaluationRepository evaluations;
    private QueueEntryRepository queueEntries;
    private CandidateRoundRepository candidateRounds;
    private InterviewerAssignmentRepository interviewerAssignments;
    private Phase3Access access;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        drives = mock(PlacementDriveRepository.class);
        applications = mock(CandidateApplicationRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        rounds = mock(InterviewRoundRepository.class);
        evaluations = mock(InterviewEvaluationRepository.class);
        queueEntries = mock(QueueEntryRepository.class);
        candidateRounds = mock(CandidateRoundRepository.class);
        interviewerAssignments = mock(InterviewerAssignmentRepository.class);
        access = mock(Phase3Access.class);
        service = new AnalyticsService(drives, applications, profiles, rounds,
                evaluations, queueEntries, candidateRounds, interviewerAssignments, access);
    }

    @Test
    void overviewRequiresDriveManagerAndUsesRequestedTenantAndPeriod() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        StatusCount completedDrive = status("COMPLETED", 2L);
        when(drives.countForAnalytics(9L, from, to)).thenReturn(2L);
        when(drives.countStatusesForAnalytics(9L, from, to))
                .thenReturn(List.of(completedDrive));
        when(applications.countForAnalytics(9L, from, to)).thenReturn(3L);
        when(applications.countStatusesForAnalytics(9L, from, to)).thenReturn(List.of());
        when(profiles.countForAnalytics(9L, from, to)).thenReturn(4L);
        when(rounds.countForAnalytics(9L, from, to)).thenReturn(5L);
        when(rounds.countStatusesForAnalytics(9L, from, to)).thenReturn(List.of());
        when(evaluations.countForAnalytics(9L, from, to)).thenReturn(6L);
        when(queueEntries.countForAnalytics(9L, from, to)).thenReturn(7L);
        when(queueEntries.countStatusesForAnalytics(9L, from, to)).thenReturn(List.of());

        var result = service.overview(4L, 9L, from, to);

        verify(access).requireDriveManager(4L, 9L);
        assertEquals(2L, result.totalPlacementDrives());
        assertEquals(2L, result.placementDrivesByStatus().get("COMPLETED"));
        assertEquals(0L, result.placementDrivesByStatus().get("DRAFT"));
        assertEquals(4L, result.totalCandidateProfiles());
        assertEquals(7L, result.totalQueueEntries());
    }

    @Test
    void statusMapsIncludeEverySupportedStatusAndAggregateReturnedCounts() {
        StatusCount submitted = status("SUBMITTED", 3L);
        StatusCount accepted = status("ACCEPTED", 2L);
        StatusCount openRound = status("OPEN", 1L);
        StatusCount closedRound = status("CLOSED", 4L);
        StatusCount waitingEntry = status("WAITING", 5L);
        StatusCount completedEntry = status("COMPLETED", 3L);
        when(applications.countStatusesForAnalytics(9L, null, null))
                .thenReturn(List.of(submitted, accepted));
        when(rounds.countStatusesForAnalytics(9L, null, null))
                .thenReturn(List.of(openRound, closedRound));
        when(queueEntries.countStatusesForAnalytics(9L, null, null))
                .thenReturn(List.of(waitingEntry, completedEntry));

        var result = service.overview(4L, 9L, null, null);

        assertEquals(ApplicationStatus.values().length,
                result.applicationsByStatus().size());
        assertEquals(3L, result.applicationsByStatus().get("SUBMITTED"));
        assertEquals(2L, result.applicationsByStatus().get("ACCEPTED"));
        assertEquals(0L, result.applicationsByStatus().get("REJECTED"));
        assertEquals(0L, result.applicationsByStatus().get("WITHDRAWN"));

        assertEquals(InterviewRoundStatus.values().length,
                result.interviewRoundsByStatus().size());
        assertEquals(1L, result.interviewRoundsByStatus().get("OPEN"));
        assertEquals(4L, result.interviewRoundsByStatus().get("CLOSED"));
        assertEquals(0L, result.interviewRoundsByStatus().get("DRAFT"));
        assertEquals(0L, result.interviewRoundsByStatus().get("PAUSED"));

        assertEquals(QueueEntryStatus.values().length,
                result.queueEntriesByStatus().size());
        assertEquals(5L, result.queueEntriesByStatus().get("WAITING"));
        assertEquals(3L, result.queueEntriesByStatus().get("COMPLETED"));
        assertEquals(0L, result.queueEntriesByStatus().get("CALLED"));
        assertEquals(0L, result.queueEntriesByStatus().get("IN_SERVICE"));
        assertEquals(0L, result.queueEntriesByStatus().get("CANCELLED"));
        assertEquals(0L, result.queueEntriesByStatus().get("NO_SHOW"));
        assertEquals(0L, result.queueEntriesByStatus().get("SKIPPED"));
    }

    @Test
    void exactFromAndToInstantsArePassedToEveryAggregateAndProfileIsTenantScoped() {
        Instant from = Instant.parse("2026-03-10T12:34:56.123Z");
        Instant to = Instant.parse("2026-03-11T12:34:56.123Z");

        service.overview(4L, 9L, from, to);

        verify(drives).countForAnalytics(9L, from, to);
        verify(drives).countStatusesForAnalytics(9L, from, to);
        verify(applications).countForAnalytics(9L, from, to);
        verify(applications).countStatusesForAnalytics(9L, from, to);
        verify(profiles).countForAnalytics(9L, from, to);
        verify(rounds).countForAnalytics(9L, from, to);
        verify(rounds).countStatusesForAnalytics(9L, from, to);
        verify(evaluations).countForAnalytics(9L, from, to);
        verify(queueEntries).countForAnalytics(9L, from, to);
        verify(queueEntries).countStatusesForAnalytics(9L, from, to);
    }

    @Test
    void candidateProfilesAreCountedOnlyForTheRequestedOrganization() {
        when(profiles.countForAnalytics(9L, null, null)).thenReturn(4L);

        var result = service.overview(4L, 9L, null, null);

        assertEquals(4L, result.totalCandidateProfiles());
        verify(profiles).countForAnalytics(9L, null, null);
        verify(profiles, never()).countForAnalytics(10L, null, null);
    }

    @Test
    void invalidDateRangeIsRejectedBeforeQueries() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(BusinessException.class, () -> service.overview(4L, 9L, date, date));

        verify(access, never()).requireDriveManager(any(), any());
        verifyNoInteractions(drives, applications, profiles, rounds, evaluations, queueEntries);
    }

    @Test
    void unauthorizedOrganizationCannotReadAnyAggregate() {
        when(access.requireDriveManager(4L, 99L))
                .thenThrow(new ForbiddenException("ORG_ADMIN or QUEUE_MANAGER role required"));

        assertThrows(ForbiddenException.class,
                () -> service.overview(4L, 99L, null, null));

        verifyNoInteractions(drives, applications, profiles, rounds, evaluations, queueEntries);
    }

    @Test
    void driveSummaryUsesTenantScopedDriveAndIncludesAllStatusMaps() {
        PlacementDrive drive = mock(PlacementDrive.class);
        when(drive.getId()).thenReturn(12L);
        when(drive.getName()).thenReturn("Campus Drive");
        when(drive.getStatus()).thenReturn(PlacementDriveStatus.REGISTRATION_OPEN);
        when(drive.getRegistrationCapacity()).thenReturn(10);
        when(drives.findByIdAndOrganizationId(12L, 9L)).thenReturn(java.util.Optional.of(drive));
        when(applications.countForDrive(9L, 12L)).thenReturn(8L);
        StatusCount submitted = status("SUBMITTED", 3L);
        StatusCount accepted = status("ACCEPTED", 2L);
        StatusCount eligible = status("ELIGIBLE", 5L);
        StatusCount qualified = status("QUALIFIED", 3L);
        StatusCount selected = status("SELECTED", 1L);
        when(applications.countStatusesForDrive(9L, 12L))
                .thenReturn(List.of(submitted, accepted));
        when(applications.countEligibilityForDrive(9L, 12L))
                .thenReturn(List.of(eligible));
        when(applications.countReservedAcceptedForDrive(9L, 12L)).thenReturn(2L);
        when(candidateRounds.countResultsForDrive(9L, 12L))
                .thenReturn(List.of(qualified, selected));

        var result = service.driveSummary(4L, 9L, 12L);

        verify(access).requireDriveManager(4L, 9L);
        assertEquals("Campus Drive", result.driveName());
        assertEquals(8L, result.totalApplications());
        assertEquals(ApplicationStatus.values().length, result.applicationsByStatus().size());
        assertEquals(0L, result.applicationsByStatus().get("REJECTED"));
        assertEquals(2L, result.applicationsByStatus().get("ACCEPTED"));
        assertEquals(EligibilityStatus.values().length,
                result.applicationsByEligibility().size());
        assertEquals(CandidateRoundResult.values().length,
                result.candidateRoundsByResult().size());
        assertEquals(2L, result.capacity().reservedAcceptedApplications());
        assertEquals(0.2, result.capacity().utilization());
    }

    @Test
    void driveSummaryReturnsNotFoundForMissingOrWrongOrganizationDrive() {
        when(drives.findByIdAndOrganizationId(12L, 99L))
                .thenReturn(java.util.Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.driveSummary(4L, 99L, 12L));
        verifyNoInteractions(applications, candidateRounds);
    }

    @Test
    void driveSummaryHandlesEmptyResultsAndInvalidCapacity() {
        PlacementDrive drive = mock(PlacementDrive.class);
        when(drive.getId()).thenReturn(12L);
        when(drive.getName()).thenReturn("Empty Drive");
        when(drive.getStatus()).thenReturn(PlacementDriveStatus.DRAFT);
        when(drive.getRegistrationCapacity()).thenReturn(0);
        when(drives.findByIdAndOrganizationId(12L, 9L)).thenReturn(java.util.Optional.of(drive));

        var result = service.driveSummary(4L, 9L, 12L);

        assertEquals(0L, result.totalApplications());
        assertEquals(0L, result.capacity().reservedAcceptedApplications());
        assertEquals(0.0, result.capacity().utilization());
        assertEquals(0L, result.applicationsByStatus().get("WITHDRAWN"));
    }

    @Test
    void applicationFunnelUsesFiveStagesAndCurrentScopedCounts() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(drives.findByIdAndOrganizationId(12L, 9L))
                .thenReturn(java.util.Optional.of(mock(PlacementDrive.class)));
        when(applications.countApplicationsForFunnel(9L, 12L, from, to)).thenReturn(10L);
        when(applications.countAcceptedForFunnel(9L, 12L, from, to)).thenReturn(8L);
        when(candidateRounds.countRegisteredForFunnel(9L, 12L, from, to)).thenReturn(6L);
        when(candidateRounds.countQualifiedForFunnel(9L, 12L, from, to)).thenReturn(4L);
        when(candidateRounds.countSelectedForFunnel(9L, 12L, from, to)).thenReturn(2L);

        var result = service.applicationFunnel(4L, 9L, 12L, from, to);

        assertEquals(List.of("Applied", "Accepted", "Interview Registered",
                        "Qualified", "Selected"),
                result.stages().stream().map(stage -> stage.stageName()).toList());
        assertEquals(10L, result.stages().get(0).count());
        assertEquals(10L, result.stages().get(0).denominator());
        assertEquals(0.8, result.stages().get(1).conversionRate());
        assertEquals(0.75, result.stages().get(2).conversionRate());
        assertEquals(0.6666666666666666, result.stages().get(3).conversionRate());
        assertEquals(0.5, result.stages().get(4).conversionRate());
    }

    @Test
    void applicationFunnelEmptyAndZeroDenominatorsAreSafe() {
        when(drives.findByIdAndOrganizationId(12L, 9L))
                .thenReturn(java.util.Optional.of(mock(PlacementDrive.class)));

        var result = service.applicationFunnel(4L, 9L, 12L, null, null);

        assertEquals(5, result.stages().size());
        assertEquals(0L, result.stages().get(0).count());
        for (var stage : result.stages()) {
            assertEquals(0.0, stage.conversionRate());
            assertFalse(Double.isNaN(stage.conversionRate()));
            assertFalse(Double.isInfinite(stage.conversionRate()));
        }
    }

    @Test
    void applicationFunnelRejectsInvalidRangeBeforeAuthorizationOrQueries() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(BusinessException.class,
                () -> service.applicationFunnel(4L, 9L, 12L, date, date));
        verifyNoInteractions(access, drives, applications, candidateRounds);
    }

    @Test
    void departmentAnalyticsMapsCountsStatusesAndRates() {
        DepartmentAnalyticsRow row = departmentRow("CSE", 3L, 4L, 1L, 2L,
                1L, 0L, 3L, 2L, 1L);
        when(applications.findDepartmentAnalytics(9L, null, null)).thenReturn(List.of(row));

        var result = service.departmentAnalytics(4L, 9L, null, null);

        verify(access).requireDriveManager(4L, 9L);
        verify(applications).findDepartmentAnalytics(9L, null, null);
        assertEquals(1, result.size());
        assertEquals("CSE", result.get(0).department());
        assertEquals(3L, result.get(0).totalCandidates());
        assertEquals(4L, result.get(0).totalApplications());
        assertEquals(2L, result.get(0).applicationsByStatus().get("ACCEPTED"));
        assertEquals(0L, result.get(0).applicationsByStatus().get("WITHDRAWN"));
        assertEquals(3L, result.get(0).interviewRegistered());
        assertEquals(2L, result.get(0).qualified());
        assertEquals(1L, result.get(0).selected());
        assertEquals(0.5, result.get(0).acceptanceRate());
        assertEquals(0.25, result.get(0).selectionRate());
    }

    @Test
    void departmentAnalyticsRejectsInvalidRangeBeforeAuthorizationOrQueries() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(BusinessException.class,
                () -> service.departmentAnalytics(4L, 9L, date, date));

        verifyNoInteractions(access, applications);
    }

    @Test
    void departmentAnalyticsReturnsEmptyListWithoutApplications() {
        when(applications.findDepartmentAnalytics(9L, null, null)).thenReturn(List.of());

        assertEquals(List.of(), service.departmentAnalytics(4L, 9L, null, null));
    }

    @Test
    void interviewerWorkloadMapsCompletionRateAndUsesAllFilters() {
        when(drives.findByIdAndOrganizationId(12L, 9L))
                .thenReturn(java.util.Optional.of(mock(PlacementDrive.class)));
        var round = mock(com.queueguard.qms.domain.InterviewRound.class);
        when(round.getPlacementDriveId()).thenReturn(12L);
        when(rounds.findByIdAndOrganizationId(7L, 9L))
                .thenReturn(java.util.Optional.of(round));
        InterviewerWorkloadRow row = mock(InterviewerWorkloadRow.class);
        when(row.getInterviewerUserId()).thenReturn(41L);
        when(row.getActiveAssignmentCount()).thenReturn(2L);
        when(row.getAssignedCandidateCount()).thenReturn(5L);
        when(row.getCompletedEvaluationCount()).thenReturn(3L);
        when(row.getPendingEvaluationCount()).thenReturn(2L);
        when(interviewerAssignments.findWorkload(9L, 12L, 7L, 41L))
                .thenReturn(List.of(row));

        var result = service.interviewerWorkload(4L, 9L, 12L, 7L, 41L);

        verify(access).requireDriveManager(4L, 9L);
        verify(drives).findByIdAndOrganizationId(12L, 9L);
        verify(rounds).findByIdAndOrganizationId(7L, 9L);
        verify(interviewerAssignments).findWorkload(9L, 12L, 7L, 41L);
        assertEquals(1, result.interviewers().size());
        assertEquals(0.6, result.interviewers().get(0).completionRate());
    }

    @Test
    void interviewerWorkloadRejectsRoundFromAnotherDriveWithoutQueryingAssignments() {
        PlacementDrive drive = mock(PlacementDrive.class);
        when(drives.findByIdAndOrganizationId(12L, 9L)).thenReturn(java.util.Optional.of(drive));
        var round = mock(com.queueguard.qms.domain.InterviewRound.class);
        when(round.getPlacementDriveId()).thenReturn(99L);
        when(rounds.findByIdAndOrganizationId(7L, 9L))
                .thenReturn(java.util.Optional.of(round));

        assertThrows(NotFoundException.class,
                () -> service.interviewerWorkload(4L, 9L, 12L, 7L, null));
        verifyNoInteractions(interviewerAssignments);
    }

    @Test
    void roundProgressionMapsOutcomesAndRatesWithoutEvaluationDateFilters() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(drives.findByIdAndOrganizationId(12L, 9L))
                .thenReturn(java.util.Optional.of(mock(PlacementDrive.class)));
        RoundProgressionRow row = mock(RoundProgressionRow.class);
        when(row.getRoundId()).thenReturn(7L);
        when(row.getRoundNumber()).thenReturn(1);
        when(row.getRoundName()).thenReturn("Technical");
        when(row.getRoundType()).thenReturn("TECHNICAL");
        when(row.getRoundStatus()).thenReturn("OPEN");
        when(row.getRegisteredCandidates()).thenReturn(5L);
        when(row.getQualifiedCandidates()).thenReturn(2L);
        when(row.getDisqualifiedCandidates()).thenReturn(1L);
        when(row.getSelectedCandidates()).thenReturn(1L);
        when(row.getHoldCandidates()).thenReturn(1L);
        when(row.getUndecidedCandidates()).thenReturn(0L);
        when(row.getEvaluationCount()).thenReturn(6L);
        when(rounds.findProgression(9L, 12L, from, to)).thenReturn(List.of(row));

        var result = service.roundProgression(4L, 9L, 12L, from, to);

        verify(rounds).findProgression(9L, 12L, from, to);
        assertEquals(1, result.rounds().size());
        assertEquals(0.4, result.rounds().get(0).qualifiedRate());
        assertEquals(0.2, result.rounds().get(0).selectedRate());
        assertEquals(6L, result.rounds().get(0).evaluationCount());
    }

    private static DepartmentAnalyticsRow departmentRow(
            String department, long candidates, long applications, long submitted,
            long accepted, long rejected, long withdrawn, long registered,
            long qualified, long selected) {
        DepartmentAnalyticsRow row = mock(DepartmentAnalyticsRow.class);
        when(row.getDepartment()).thenReturn(department);
        when(row.getTotalCandidates()).thenReturn(candidates);
        when(row.getTotalApplications()).thenReturn(applications);
        when(row.getSubmitted()).thenReturn(submitted);
        when(row.getAccepted()).thenReturn(accepted);
        when(row.getRejected()).thenReturn(rejected);
        when(row.getWithdrawn()).thenReturn(withdrawn);
        when(row.getInterviewRegistered()).thenReturn(registered);
        when(row.getQualified()).thenReturn(qualified);
        when(row.getSelected()).thenReturn(selected);
        return row;
    }

    private static StatusCount status(String name, long count) {
        StatusCount row = mock(StatusCount.class);
        when(row.getStatus()).thenReturn(name);
        when(row.getCount()).thenReturn(count);
        return row;
    }
}

package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.qms.domain.ApplicationStatus;
import com.queueguard.qms.domain.CandidateRoundResult;
import com.queueguard.qms.domain.EligibilityStatus;
import com.queueguard.qms.domain.InterviewRoundStatus;
import com.queueguard.qms.domain.PlacementDrive;
import com.queueguard.qms.domain.PlacementDriveStatus;
import com.queueguard.qms.domain.QueueEntryStatus;
import com.queueguard.qms.dto.AnalyticsDtos.ApplicationFunnel;
import com.queueguard.qms.dto.AnalyticsDtos.ApplicationFunnelStage;
import com.queueguard.qms.dto.AnalyticsDtos.DepartmentAnalytics;
import com.queueguard.qms.dto.AnalyticsDtos.DriveAnalyticsSummary;
import com.queueguard.qms.dto.AnalyticsDtos.DriveCapacitySummary;
import com.queueguard.qms.dto.AnalyticsDtos.OrganizationOverview;
import com.queueguard.qms.dto.AnalyticsDtos.InterviewerWorkload;
import com.queueguard.qms.dto.AnalyticsDtos.InterviewerWorkloadRow;
import com.queueguard.qms.dto.AnalyticsDtos.RoundProgression;
import com.queueguard.qms.dto.AnalyticsDtos.RoundProgressionRow;
import com.queueguard.qms.repo.CandidateApplicationRepository;
import com.queueguard.qms.repo.CandidateProfileRepository;
import com.queueguard.qms.repo.CandidateRoundRepository;
import com.queueguard.qms.repo.DepartmentAnalyticsRow;
import com.queueguard.qms.repo.InterviewEvaluationRepository;
import com.queueguard.qms.repo.InterviewerAssignmentRepository;
import com.queueguard.qms.repo.InterviewRoundRepository;
import com.queueguard.qms.repo.PlacementDriveRepository;
import com.queueguard.qms.repo.QueueEntryRepository;
import com.queueguard.qms.repo.StatusCount;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization overview aggregates. Drives, profiles, rounds, and queue entries
 * use their creation/join timestamps; applications use applied_at; evaluations
 * use submitted_at. Counts are current status snapshots of records selected by
 * those activity timestamps, not historical status-transition counts.
 */
@Service
public class AnalyticsService {
    private final PlacementDriveRepository drives;
    private final CandidateApplicationRepository applications;
    private final CandidateProfileRepository profiles;
    private final InterviewRoundRepository rounds;
    private final InterviewEvaluationRepository evaluations;
    private final QueueEntryRepository queueEntries;
    private final CandidateRoundRepository candidateRounds;
    private final InterviewerAssignmentRepository interviewerAssignments;
    private final Phase3Access access;

    @Autowired
    public AnalyticsService(
            PlacementDriveRepository drives,
            CandidateApplicationRepository applications,
            CandidateProfileRepository profiles,
            InterviewRoundRepository rounds,
            InterviewEvaluationRepository evaluations,
            QueueEntryRepository queueEntries,
            CandidateRoundRepository candidateRounds,
            InterviewerAssignmentRepository interviewerAssignments,
            Phase3Access access) {
        this.drives = drives;
        this.applications = applications;
        this.profiles = profiles;
        this.rounds = rounds;
        this.evaluations = evaluations;
        this.queueEntries = queueEntries;
        this.candidateRounds = candidateRounds;
        this.interviewerAssignments = interviewerAssignments;
        this.access = access;
    }

    public AnalyticsService(
            PlacementDriveRepository drives,
            CandidateApplicationRepository applications,
            CandidateProfileRepository profiles,
            InterviewRoundRepository rounds,
            InterviewEvaluationRepository evaluations,
            QueueEntryRepository queueEntries,
            Phase3Access access) {
        this(drives, applications, profiles, rounds, evaluations, queueEntries,
                null, null, access);
    }

    public AnalyticsService(
            PlacementDriveRepository drives,
            CandidateApplicationRepository applications,
            CandidateProfileRepository profiles,
            InterviewRoundRepository rounds,
            InterviewEvaluationRepository evaluations,
            QueueEntryRepository queueEntries,
            CandidateRoundRepository candidateRounds,
            Phase3Access access) {
        this(drives, applications, profiles, rounds, evaluations, queueEntries,
                candidateRounds, null, access);
    }

    @Transactional(readOnly = true)
    public OrganizationOverview overview(
            Long actorId, Long organizationId, Instant from, Instant to) {
        validateRange(from, to);
        access.requireDriveManager(actorId, organizationId);

        return new OrganizationOverview(
                from,
                to,
                drives.countForAnalytics(organizationId, from, to),
                statuses(PlacementDriveStatus.values(),
                        drives.countStatusesForAnalytics(organizationId, from, to)),
                applications.countForAnalytics(organizationId, from, to),
                statuses(ApplicationStatus.values(),
                        applications.countStatusesForAnalytics(organizationId, from, to)),
                profiles.countForAnalytics(organizationId, from, to),
                rounds.countForAnalytics(organizationId, from, to),
                statuses(InterviewRoundStatus.values(),
                        rounds.countStatusesForAnalytics(organizationId, from, to)),
                evaluations.countForAnalytics(organizationId, from, to),
                queueEntries.countForAnalytics(organizationId, from, to),
                statuses(QueueEntryStatus.values(),
                        queueEntries.countStatusesForAnalytics(organizationId, from, to)));
    }

    @Transactional(readOnly = true)
    public DriveAnalyticsSummary driveSummary(
            Long actorId, Long organizationId, Long driveId) {
        access.requireDriveManager(actorId, organizationId);
        PlacementDrive drive = drives.findByIdAndOrganizationId(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));

        long reserved = applications.countReservedAcceptedForDrive(organizationId, driveId);
        int capacity = drive.getRegistrationCapacity();
        DriveCapacitySummary capacitySummary = new DriveCapacitySummary(
                reserved,
                capacity,
                safeRate(reserved, capacity));

        return new DriveAnalyticsSummary(
                drive.getId(),
                drive.getName(),
                drive.getStatus().name(),
                capacity,
                applications.countForDrive(organizationId, driveId),
                statuses(ApplicationStatus.values(),
                        applications.countStatusesForDrive(organizationId, driveId)),
                statuses(EligibilityStatus.values(),
                        applications.countEligibilityForDrive(organizationId, driveId)),
                statuses(CandidateRoundResult.values(),
                        candidateRounds.countResultsForDrive(organizationId, driveId)),
                capacitySummary);
    }

    @Transactional(readOnly = true)
    public ApplicationFunnel applicationFunnel(
            Long actorId, Long organizationId, Long driveId, Instant from, Instant to) {
        validateRange(from, to);
        access.requireDriveManager(actorId, organizationId);
        drives.findByIdAndOrganizationId(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));

        long applied = applications.countApplicationsForFunnel(
                organizationId, driveId, from, to);
        long accepted = applications.countAcceptedForFunnel(
                organizationId, driveId, from, to);
        long registered = candidateRounds.countRegisteredForFunnel(
                organizationId, driveId, from, to);
        long qualified = candidateRounds.countQualifiedForFunnel(
                organizationId, driveId, from, to);
        long selected = candidateRounds.countSelectedForFunnel(
                organizationId, driveId, from, to);

        List<ApplicationFunnelStage> stages = List.of(
                new ApplicationFunnelStage("Applied", applied, applied,
                        applied == 0 ? 0.0 : 1.0),
                new ApplicationFunnelStage("Accepted", accepted, applied,
                        safeRate(accepted, applied)),
                new ApplicationFunnelStage("Interview Registered", registered, accepted,
                        safeRate(registered, accepted)),
                new ApplicationFunnelStage("Qualified", qualified, registered,
                        safeRate(qualified, registered)),
                new ApplicationFunnelStage("Selected", selected, qualified,
                        safeRate(selected, qualified)));

        return new ApplicationFunnel(from, to, stages);
    }

    @Transactional(readOnly = true)
    public List<DepartmentAnalytics> departmentAnalytics(
            Long actorId, Long organizationId, Instant from, Instant to) {
        validateRange(from, to);
        access.requireDriveManager(actorId, organizationId);

        return applications.findDepartmentAnalytics(organizationId, from, to).stream()
                .map(AnalyticsService::toDepartmentAnalytics)
                .toList();
    }

    @Transactional(readOnly = true)
    public InterviewerWorkload interviewerWorkload(
            Long actorId, Long organizationId, Long driveId,
            Long roundId, Long interviewerId) {
        access.requireDriveManager(actorId, organizationId);
        if (driveId != null) {
            requireDrive(organizationId, driveId);
        }
        if (roundId != null) {
            var round = rounds.findByIdAndOrganizationId(roundId, organizationId)
                    .orElseThrow(() -> new NotFoundException("Interview round not found"));
            if (driveId != null && !driveId.equals(round.getPlacementDriveId())) {
                throw new NotFoundException("Interview round not found");
            }
        }
        if (interviewerAssignments == null) {
            throw new IllegalStateException("Interviewer assignment analytics is unavailable");
        }

        return new InterviewerWorkload(interviewerAssignments.findWorkload(
                        organizationId, driveId, roundId, interviewerId).stream()
                .map(row -> new InterviewerWorkloadRow(
                        row.getInterviewerUserId(),
                        row.getActiveAssignmentCount(),
                        row.getAssignedCandidateCount(),
                        row.getCompletedEvaluationCount(),
                        row.getPendingEvaluationCount(),
                        safeRate(row.getCompletedEvaluationCount(),
                                row.getCompletedEvaluationCount()
                                        + row.getPendingEvaluationCount())))
                .toList());
    }

    @Transactional(readOnly = true)
    public RoundProgression roundProgression(
            Long actorId, Long organizationId, Long driveId, Instant from, Instant to) {
        validateRange(from, to);
        access.requireDriveManager(actorId, organizationId);
        requireDrive(organizationId, driveId);

        return new RoundProgression(
                from,
                to,
                driveId,
                rounds.findProgression(organizationId, driveId, from, to).stream()
                        .map(row -> new RoundProgressionRow(
                                row.getRoundId(),
                                row.getRoundNumber(),
                                row.getRoundName(),
                                row.getRoundType(),
                                row.getRoundStatus(),
                                row.getRegisteredCandidates(),
                                row.getQualifiedCandidates(),
                                row.getDisqualifiedCandidates(),
                                row.getSelectedCandidates(),
                                row.getHoldCandidates(),
                                row.getUndecidedCandidates(),
                                row.getEvaluationCount(),
                                safeRate(row.getQualifiedCandidates(),
                                        row.getRegisteredCandidates()),
                                safeRate(row.getSelectedCandidates(),
                                        row.getRegisteredCandidates())))
                        .toList());
    }

    private void requireDrive(Long organizationId, Long driveId) {
        drives.findByIdAndOrganizationId(driveId, organizationId)
                .orElseThrow(() -> new NotFoundException("Placement drive not found"));
    }

    private static DepartmentAnalytics toDepartmentAnalytics(
            DepartmentAnalyticsRow row) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Arrays.stream(ApplicationStatus.values())
                .forEach(status -> statusCounts.put(status.name(), 0L));
        statusCounts.put(ApplicationStatus.SUBMITTED.name(), row.getSubmitted());
        statusCounts.put(ApplicationStatus.ACCEPTED.name(), row.getAccepted());
        statusCounts.put(ApplicationStatus.REJECTED.name(), row.getRejected());
        statusCounts.put(ApplicationStatus.WITHDRAWN.name(), row.getWithdrawn());

        return new DepartmentAnalytics(
                row.getDepartment(),
                row.getTotalCandidates(),
                row.getTotalApplications(),
                statusCounts,
                row.getInterviewRegistered(),
                row.getQualified(),
                row.getSelected(),
                safeRate(row.getAccepted(), row.getTotalApplications()),
                safeRate(row.getSelected(), row.getTotalApplications()));
    }

    private static <E extends Enum<E>> Map<String, Long> statuses(
            E[] supportedStatuses, Iterable<StatusCount> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        Arrays.stream(supportedStatuses).forEach(status -> result.put(status.name(), 0L));
        for (StatusCount row : rows) {
            if (result.containsKey(row.getStatus())) {
                result.put(row.getStatus(), row.getCount());
            }
        }
        return result;
    }

    private static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(400, "INVALID_DATE_RANGE",
                    "The from date must be earlier than the to date");
        }
    }

    private static double safeRate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / (double) denominator;
    }
}

package com.queueguard.qms.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalyticsDtos {
    private AnalyticsDtos() {
    }

    /**
     * Organization overview state for records whose source timestamp is in [from, to).
     * A null bound means unbounded. Status maps contain all schema-supported statuses,
     * including statuses with a zero count. Status values are the current state of
     * records whose relevant activity timestamp falls in [from, to); they are not
     * historical status values as of "to".
     */
    public record OrganizationOverview(
            Instant from,
            Instant to,
            long totalPlacementDrives,
            Map<String, Long> placementDrivesByStatus,
            long totalCandidateApplications,
            Map<String, Long> applicationsByStatus,
            long totalCandidateProfiles,
            long totalInterviewRounds,
            Map<String, Long> interviewRoundsByStatus,
            long totalInterviewEvaluations,
            long totalQueueEntries,
            Map<String, Long> queueEntriesByStatus) {
    }

    public record DriveCapacitySummary(
            long reservedAcceptedApplications,
            int registrationCapacity,
            double utilization) {
        public double utilizationRate() { return utilization(); }
        public double capacityUtilization() { return utilization(); }
    }

    public record DriveAnalyticsSummary(
            Long driveId,
            String driveName,
            String driveStatus,
            int registrationCapacity,
            long totalApplications,
            Map<String, Long> applicationsByStatus,
            Map<String, Long> applicationsByEligibility,
            Map<String, Long> candidateRoundsByResult,
            DriveCapacitySummary capacity) {
        public Map<String, Long> applicationStatusDistribution() { return applicationsByStatus(); }
        public Map<String, Long> eligibilityStatusDistribution() { return applicationsByEligibility(); }
        public Map<String, Long> eligibilityDistribution() { return applicationsByEligibility(); }
        public Map<String, Long> candidateRoundResultDistribution() { return candidateRoundsByResult(); }
        public DriveCapacitySummary capacityUtilization() { return capacity(); }
    }

    public record ApplicationFunnelStage(
            String stageName,
            long count,
            long denominator,
            double conversionRate) {
        public String name() { return stageName(); }
    }

    public record ApplicationFunnel(
            Instant from,
            Instant to,
            List<ApplicationFunnelStage> stages) {
        public List<ApplicationFunnelStage> applicationFunnelStages() { return stages(); }
    }

    public record DepartmentAnalytics(
            String department,
            long totalCandidates,
            long totalApplications,
            Map<String, Long> applicationsByStatus,
            long interviewRegistered,
            long qualified,
            long selected,
            double acceptanceRate,
            double selectionRate) {
    }

    public record InterviewerWorkload(
            List<InterviewerWorkloadRow> interviewers) {
    }

    public record InterviewerWorkloadRow(
            Long interviewerUserId,
            long activeAssignmentCount,
            long assignedCandidateCount,
            long completedEvaluationCount,
            long pendingEvaluationCount,
            double completionRate) {
    }

    public record RoundProgression(
            Instant from,
            Instant to,
            Long driveId,
            List<RoundProgressionRow> rounds) {
    }

    public record RoundProgressionRow(
            Long roundId,
            Integer roundNumber,
            String roundName,
            String roundType,
            String roundStatus,
            long registeredCandidates,
            long qualifiedCandidates,
            long disqualifiedCandidates,
            long selectedCandidates,
            long holdCandidates,
            long undecidedCandidates,
            long evaluationCount,
            double qualifiedRate,
            double selectedRate) {
    }
}

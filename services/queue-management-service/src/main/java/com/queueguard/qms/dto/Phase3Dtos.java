package com.queueguard.qms.dto;

import com.queueguard.qms.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class Phase3Dtos {
    private Phase3Dtos() {}

    public record PlacementDriveRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            @NotNull @Positive Integer registrationCapacity,
            Instant registrationOpensAt,
            Instant registrationClosesAt) {
        @AssertTrue(message = "Registration close time must be after its open time")
        @JsonIgnore
        public boolean isRegistrationWindowOrdered() {
            return registrationOpensAt == null || registrationClosesAt == null
                    || registrationClosesAt.isAfter(registrationOpensAt);
        }
    }

    public record UpdatePlacementDriveRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            Instant registrationOpensAt,
            Instant registrationClosesAt) {
        @AssertTrue(message = "Registration close time must be after its open time")
        @JsonIgnore
        public boolean isRegistrationWindowOrdered() {
            return registrationOpensAt == null || registrationClosesAt == null
                    || registrationClosesAt.isAfter(registrationOpensAt);
        }
    }

    public record CapacityRequest(@NotNull @Positive Integer capacity) {}

    public record PlacementDriveView(
            Long id, Long organizationId, String name, String description,
            Integer registrationCapacity, PlacementDriveStatus status,
            Instant registrationOpensAt, Instant registrationClosesAt,
            Long createdBy, Instant createdAt, Instant updatedAt) {
        public static PlacementDriveView from(PlacementDrive drive) {
            return new PlacementDriveView(drive.getId(), drive.getOrganizationId(),
                    drive.getName(), drive.getDescription(), drive.getRegistrationCapacity(),
                    drive.getStatus(), drive.getRegistrationOpensAt(),
                    drive.getRegistrationClosesAt(), drive.getCreatedBy(),
                    drive.getCreatedAt(), drive.getUpdatedAt());
        }
    }

    public record CandidateProfileRequest(
            @NotBlank @Size(max = 255) String studentName,
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 100) String registrationNumber,
            @Size(max = 50) String phone,
            @Size(max = 255) String department,
            @Min(1900) Integer graduationYear,
            @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal cgpa,
            @Size(max = 1000) String resumeReference,
            @Size(max = 10000) String skills) {}

    public record CandidateProfileView(
            Long id, Long organizationId, Long userId, String studentName,
            String registrationNumber, String email, String phone, String department,
            Integer graduationYear, BigDecimal cgpa, String resumeReference, String skills,
            Instant createdAt, Instant updatedAt) {
        public static CandidateProfileView from(CandidateProfile profile) {
            return new CandidateProfileView(profile.getId(), profile.getOrganizationId(),
                    profile.getUserId(), profile.getStudentName(),
                    profile.getRegistrationNumber(), profile.getEmail(), profile.getPhone(),
                    profile.getDepartment(), profile.getGraduationYear(), profile.getCgpa(),
                    profile.getResumeReference(), profile.getSkills(),
                    profile.getCreatedAt(), profile.getUpdatedAt());
        }
    }

    public record SubmitApplicationRequest(@NotNull @Positive Long candidateProfileId) {}

    public record EligibilityRequest(
            @NotNull EligibilityStatus eligibilityStatus,
            @Size(max = 2000) String reason) {}

    public record WithdrawApplicationRequest(@Size(max = 2000) String reason) {}

    public record CandidateApplicationView(
            Long id, Long organizationId, Long placementDriveId, Long applicationNumber,
            EligibilityStatus eligibilityStatus, String eligibilityReason,
            ApplicationStatus applicationStatus, Instant appliedAt, String withdrawalReason,
            Long candidateProfileId, Long studentUserId, Instant capacityReservedAt,
            Instant createdAt, Instant updatedAt) {
        public static CandidateApplicationView from(CandidateApplication application) {
            return new CandidateApplicationView(application.getId(),
                    application.getOrganizationId(), application.getPlacementDriveId(),
                    application.getApplicationNumber(), application.getEligibilityStatus(),
                    application.getEligibilityReason(), application.getApplicationStatus(),
                    application.getAppliedAt(), application.getWithdrawalReason(),
                    application.getCandidateProfileId(), application.getStudentUserId(),
                    application.getCapacityReservedAt(), application.getCreatedAt(),
                    application.getUpdatedAt());
        }
    }

    public record InterviewRoundRequest(
            @NotNull @Positive Long queueConfigurationId,
            @NotNull @Positive Integer roundNumber,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            @NotNull InterviewRoundType roundType,
            @NotNull Instant scheduledStartAt,
            @NotNull Instant scheduledEndAt,
            @NotBlank @Size(max = 4000) String qualificationCriteria) {
        @AssertTrue(message = "Interview end time must be after its start time")
        @JsonIgnore
        public boolean isScheduleOrdered() {
            return scheduledStartAt != null && scheduledEndAt != null
                    && scheduledEndAt.isAfter(scheduledStartAt);
        }
    }

    public record UpdateInterviewRoundRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            @NotNull InterviewRoundType roundType,
            @NotNull Instant scheduledStartAt,
            @NotNull Instant scheduledEndAt,
            @NotBlank @Size(max = 4000) String qualificationCriteria) {
        @AssertTrue(message = "Interview end time must be after its start time")
        @JsonIgnore
        public boolean isScheduleOrdered() {
            return scheduledStartAt != null && scheduledEndAt != null
                    && scheduledEndAt.isAfter(scheduledStartAt);
        }
    }

    public record InterviewRoundView(
            Long id, Long organizationId, Long placementDriveId, Long queueConfigurationId,
            Integer roundNumber, String name, String description, InterviewRoundType roundType,
            Instant scheduledStartAt, Instant scheduledEndAt, InterviewRoundStatus status,
            String qualificationCriteria, Long createdBy, Instant createdAt, Instant updatedAt) {
        public static InterviewRoundView from(InterviewRound round) {
            return new InterviewRoundView(round.getId(), round.getOrganizationId(),
                    round.getPlacementDriveId(), round.getQueueConfigurationId(),
                    round.getRoundNumber(), round.getName(), round.getDescription(),
                    round.getRoundType(), round.getScheduledStartAt(), round.getScheduledEndAt(),
                    round.getStatus(), round.getQualificationCriteria(), round.getCreatedBy(),
                    round.getCreatedAt(), round.getUpdatedAt());
        }
    }

    public record CreateInterviewerAssignmentRequest(
            @NotNull @Positive Long interviewerUserId,
            @Size(max = 255) String panelName,
            @Size(max = 100) String servicePoint) {}

    public record ChangeInterviewerAssignmentStatusRequest(
            @NotNull InterviewerAssignmentStatus status) {}

    public record InterviewerAssignmentView(
            Long id, Long organizationId, Long interviewRoundId, Long interviewerUserId,
            String panelName, String servicePoint, Instant assignedAt,
            InterviewerAssignmentStatus status, Instant createdAt, Instant updatedAt) {
        public static InterviewerAssignmentView from(InterviewerAssignment assignment) {
            return new InterviewerAssignmentView(assignment.getId(),
                    assignment.getOrganizationId(), assignment.getInterviewRoundId(),
                    assignment.getInterviewerUserId(), assignment.getPanelName(),
                    assignment.getServicePoint(), assignment.getAssignedAt(),
                    assignment.getStatus(), assignment.getCreatedAt(), assignment.getUpdatedAt());
        }
    }
}

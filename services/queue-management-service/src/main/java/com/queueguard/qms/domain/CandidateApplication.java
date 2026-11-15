package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "candidate_applications",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_candidate_application_org_id",
                    columnNames = {"organization_id", "id"}),
            @UniqueConstraint(name = "uq_candidate_application_number",
                    columnNames = {"organization_id", "placement_drive_id", "application_number"}),
            @UniqueConstraint(name = "uq_candidate_application_profile_drive",
                    columnNames = {"organization_id", "placement_drive_id", "candidate_profile_id"}),
            @UniqueConstraint(name = "uq_candidate_application_org_drive_id",
                    columnNames = {"organization_id", "id", "placement_drive_id"})
        })
public class CandidateApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "placement_drive_id", nullable = false) private Long placementDriveId;
    @Column(name = "application_number", nullable = false) private Long applicationNumber;
    @Enumerated(EnumType.STRING) @Column(name = "eligibility_status", nullable = false, length = 20)
    private EligibilityStatus eligibilityStatus = EligibilityStatus.PENDING;
    @Column(name = "eligibility_reason", length = 2000) private String eligibilityReason;
    @Enumerated(EnumType.STRING) @Column(name = "application_status", nullable = false, length = 20)
    private ApplicationStatus applicationStatus = ApplicationStatus.SUBMITTED;
    @Column(name = "applied_at", nullable = false) private Instant appliedAt = Instant.now();
    @Column(name = "withdrawal_reason", length = 2000) private String withdrawalReason;
    @Column(name = "candidate_profile_id", nullable = false) private Long candidateProfileId;
    @Column(name = "student_user_id", nullable = false) private Long studentUserId;
    @Column(name = "capacity_reserved_at") private Instant capacityReservedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getPlacementDriveId() { return placementDriveId; } public void setPlacementDriveId(Long v) { placementDriveId = v; }
    public Long getApplicationNumber() { return applicationNumber; } public void setApplicationNumber(Long v) { applicationNumber = v; }
    public EligibilityStatus getEligibilityStatus() { return eligibilityStatus; } public void setEligibilityStatus(EligibilityStatus v) { eligibilityStatus = v; }
    public String getEligibilityReason() { return eligibilityReason; } public void setEligibilityReason(String v) { eligibilityReason = v; }
    public ApplicationStatus getApplicationStatus() { return applicationStatus; } public void setApplicationStatus(ApplicationStatus v) { applicationStatus = v; }
    public Instant getAppliedAt() { return appliedAt; }
    public String getWithdrawalReason() { return withdrawalReason; } public void setWithdrawalReason(String v) { withdrawalReason = v; }
    public Long getCandidateProfileId() { return candidateProfileId; } public void setCandidateProfileId(Long v) { candidateProfileId = v; }
    public Long getStudentUserId() { return studentUserId; } public void setStudentUserId(Long v) { studentUserId = v; }
    public Instant getCapacityReservedAt() { return capacityReservedAt; } public void setCapacityReservedAt(Instant v) { capacityReservedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

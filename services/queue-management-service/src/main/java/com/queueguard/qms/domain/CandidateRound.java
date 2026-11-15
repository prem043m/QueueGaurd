package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "candidate_rounds",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_candidate_round_org_id", columnNames = {"organization_id", "id"}),
            @UniqueConstraint(name = "uq_candidate_round_application_round", columnNames = {"organization_id", "candidate_application_id", "interview_round_id"}),
            @UniqueConstraint(name = "uq_candidate_round_queue_entry", columnNames = {"organization_id", "queue_entry_id"})
        })
public class CandidateRound {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "candidate_application_id", nullable = false) private Long candidateApplicationId;
    @Column(name = "interview_round_id", nullable = false) private Long interviewRoundId;
    @Column(name = "placement_drive_id", nullable = false) private Long placementDriveId;
    @Column(name = "queue_configuration_id", nullable = false) private Long queueConfigurationId;
    @Column(name = "queue_entry_id", nullable = false) private Long queueEntryId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private CandidateRoundResult result;
    @Column(name = "decision_reason", length = 2000) private String decisionReason;
    @Column(name = "decided_by") private Long decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getCandidateApplicationId() { return candidateApplicationId; } public void setCandidateApplicationId(Long v) { candidateApplicationId = v; }
    public Long getInterviewRoundId() { return interviewRoundId; } public void setInterviewRoundId(Long v) { interviewRoundId = v; }
    public Long getPlacementDriveId() { return placementDriveId; } public void setPlacementDriveId(Long v) { placementDriveId = v; }
    public Long getQueueConfigurationId() { return queueConfigurationId; } public void setQueueConfigurationId(Long v) { queueConfigurationId = v; }
    public Long getQueueEntryId() { return queueEntryId; } public void setQueueEntryId(Long v) { queueEntryId = v; }
    public CandidateRoundResult getResult() { return result; } public void setResult(CandidateRoundResult v) { result = v; }
    public String getDecisionReason() { return decisionReason; } public void setDecisionReason(String v) { decisionReason = v; }
    public Long getDecidedBy() { return decidedBy; } public void setDecidedBy(Long v) { decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; } public void setDecidedAt(Instant v) { decidedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "interview_rounds",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_interview_round_org_id", columnNames = {"organization_id", "id"}),
            @UniqueConstraint(name = "uq_interview_round_number", columnNames = {"organization_id", "placement_drive_id", "round_number"}),
            @UniqueConstraint(name = "uq_interview_round_drive_queue", columnNames = {"organization_id", "id", "placement_drive_id", "queue_configuration_id"})
        })
public class InterviewRound {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "placement_drive_id", nullable = false) private Long placementDriveId;
    @Column(name = "queue_configuration_id", nullable = false) private Long queueConfigurationId;
    @Column(name = "round_number", nullable = false) private Integer roundNumber;
    @Column(nullable = false, length = 255) private String name;
    @Column(length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(name = "round_type", nullable = false, length = 30)
    private InterviewRoundType roundType;
    @Column(name = "scheduled_start_at", nullable = false) private Instant scheduledStartAt;
    @Column(name = "scheduled_end_at", nullable = false) private Instant scheduledEndAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private InterviewRoundStatus status = InterviewRoundStatus.DRAFT;
    @Column(name = "qualification_criteria", nullable = false, columnDefinition = "TEXT")
    private String qualificationCriteria;
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getPlacementDriveId() { return placementDriveId; } public void setPlacementDriveId(Long v) { placementDriveId = v; }
    public Long getQueueConfigurationId() { return queueConfigurationId; } public void setQueueConfigurationId(Long v) { queueConfigurationId = v; }
    public Integer getRoundNumber() { return roundNumber; } public void setRoundNumber(Integer v) { roundNumber = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public InterviewRoundType getRoundType() { return roundType; } public void setRoundType(InterviewRoundType v) { roundType = v; }
    public Instant getScheduledStartAt() { return scheduledStartAt; } public void setScheduledStartAt(Instant v) { scheduledStartAt = v; }
    public Instant getScheduledEndAt() { return scheduledEndAt; } public void setScheduledEndAt(Instant v) { scheduledEndAt = v; }
    public InterviewRoundStatus getStatus() { return status; } public void setStatus(InterviewRoundStatus v) { status = v; }
    public String getQualificationCriteria() { return qualificationCriteria; } public void setQualificationCriteria(String v) { qualificationCriteria = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

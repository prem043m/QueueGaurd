package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "interviewer_assignments",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_interviewer_assignment_org_id", columnNames = {"organization_id", "id"})
        })
public class InterviewerAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "interview_round_id", nullable = false) private Long interviewRoundId;
    @Column(name = "interviewer_user_id", nullable = false) private Long interviewerUserId;
    @Column(name = "panel_name", length = 255) private String panelName;
    @Column(name = "service_point", length = 100) private String servicePoint;
    @Column(name = "assigned_at", nullable = false) private Instant assignedAt = Instant.now();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private InterviewerAssignmentStatus status = InterviewerAssignmentStatus.ASSIGNED;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate void touched() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getInterviewRoundId() { return interviewRoundId; } public void setInterviewRoundId(Long v) { interviewRoundId = v; }
    public Long getInterviewerUserId() { return interviewerUserId; } public void setInterviewerUserId(Long v) { interviewerUserId = v; }
    public String getPanelName() { return panelName; } public void setPanelName(String v) { panelName = v; }
    public String getServicePoint() { return servicePoint; } public void setServicePoint(String v) { servicePoint = v; }
    public Instant getAssignedAt() { return assignedAt; } public void setAssignedAt(Instant v) { assignedAt = v; }
    public InterviewerAssignmentStatus getStatus() { return status; } public void setStatus(InterviewerAssignmentStatus v) { status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

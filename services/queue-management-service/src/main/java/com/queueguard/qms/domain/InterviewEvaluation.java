package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "interview_evaluations",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_interview_evaluation_org_id", columnNames = {"organization_id", "id"}),
            @UniqueConstraint(name = "uq_interview_evaluation_reviewer", columnNames = {"organization_id", "candidate_round_id", "interviewer_user_id"})
        })
public class InterviewEvaluation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "organization_id", nullable = false) private Long organizationId;
    @Column(name = "candidate_round_id", nullable = false) private Long candidateRoundId;
    @Column(name = "interviewer_user_id", nullable = false) private Long interviewerUserId;
    @Column(name = "technical_score", nullable = false, precision = 5, scale = 2) private BigDecimal technicalScore;
    @Column(name = "communication_score", nullable = false, precision = 5, scale = 2) private BigDecimal communicationScore;
    @Column(name = "problem_solving_score", nullable = false, precision = 5, scale = 2) private BigDecimal problemSolvingScore;
    @Column(name = "overall_score", nullable = false, precision = 5, scale = 2) private BigDecimal overallScore;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EvaluationRecommendation recommendation;
    @Column(nullable = false, columnDefinition = "TEXT") private String feedback;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version @Column(nullable = false) private Long version = 0L;
    @PreUpdate
    void preventUpdate() {
        rejectMutation();
    }

    @PreRemove
    void preventDelete() {
        rejectMutation();
    }

    private void rejectMutation() {
        throw new IllegalStateException("Submitted interview evaluations are immutable");
    }

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { organizationId = v; }
    public Long getCandidateRoundId() { return candidateRoundId; } public void setCandidateRoundId(Long v) { candidateRoundId = v; }
    public Long getInterviewerUserId() { return interviewerUserId; } public void setInterviewerUserId(Long v) { interviewerUserId = v; }
    public BigDecimal getTechnicalScore() { return technicalScore; } public void setTechnicalScore(BigDecimal v) { technicalScore = v; }
    public BigDecimal getCommunicationScore() { return communicationScore; } public void setCommunicationScore(BigDecimal v) { communicationScore = v; }
    public BigDecimal getProblemSolvingScore() { return problemSolvingScore; } public void setProblemSolvingScore(BigDecimal v) { problemSolvingScore = v; }
    public BigDecimal getOverallScore() { return overallScore; } public void setOverallScore(BigDecimal v) { overallScore = v; }
    public EvaluationRecommendation getRecommendation() { return recommendation; } public void setRecommendation(EvaluationRecommendation v) { recommendation = v; }
    public String getFeedback() { return feedback; } public void setFeedback(String v) { feedback = v; }
    public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant v) { submittedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

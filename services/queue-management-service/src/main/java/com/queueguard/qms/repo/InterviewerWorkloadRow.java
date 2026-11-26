package com.queueguard.qms.repo;

public interface InterviewerWorkloadRow {
    Long getInterviewerUserId();

    long getActiveAssignmentCount();

    long getAssignedCandidateCount();

    long getCompletedEvaluationCount();

    long getPendingEvaluationCount();
}

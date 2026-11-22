package com.queueguard.qms.repo;

public interface DepartmentAnalyticsRow {
    String getDepartment();

    long getTotalCandidates();

    long getTotalApplications();

    long getSubmitted();

    long getAccepted();

    long getRejected();

    long getWithdrawn();

    long getInterviewRegistered();

    long getQualified();

    long getSelected();
}

package com.queueguard.qms.repo;

public interface RoundProgressionRow {
    Long getRoundId();

    Integer getRoundNumber();

    String getRoundName();

    String getRoundType();

    String getRoundStatus();

    long getRegisteredCandidates();

    long getQualifiedCandidates();

    long getDisqualifiedCandidates();

    long getSelectedCandidates();

    long getHoldCandidates();

    long getUndecidedCandidates();

    long getEvaluationCount();
}

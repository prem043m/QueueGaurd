package com.queueguard.qms.service;

public final class ConsumerFailureClassifier {
    private ConsumerFailureClassifier() {
    }

    public static FailureKind classify(Throwable failure) {
        return failure instanceof PermanentEventException
                ? FailureKind.PERMANENT
                : FailureKind.TRANSIENT;
    }

    public enum FailureKind {
        PERMANENT,
        TRANSIENT
    }
}

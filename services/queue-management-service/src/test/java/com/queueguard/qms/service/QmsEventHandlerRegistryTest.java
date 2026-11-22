package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QmsEventHandlerRegistryTest {
    @Test
    void selectsDeclaredHandlerAndRejectsUnsupportedEvents() {
        QmsEventHandler handler = handler(Set.of("EVALUATION_SUBMITTED"));
        QmsEventHandlerRegistry registry = new QmsEventHandlerRegistry(List.of(handler));

        assertEquals(handler, registry.requireHandler("EVALUATION_SUBMITTED"));
        assertThrows(PermanentEventException.class,
                () -> registry.requireHandler("UNRECOGNIZED_EVENT"));
        assertEquals(ConsumerFailureClassifier.FailureKind.PERMANENT,
                ConsumerFailureClassifier.classify(
                        new PermanentEventException("unsupported")));
        assertEquals(ConsumerFailureClassifier.FailureKind.TRANSIENT,
                ConsumerFailureClassifier.classify(new IllegalStateException("temporary")));
    }

    @Test
    void rejectsOverlappingHandlerDeclarationsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new QmsEventHandlerRegistry(List.of(
                handler(Set.of("EVALUATION_SUBMITTED")),
                handler(Set.of("EVALUATION_SUBMITTED")))));
    }

    private static QmsEventHandler handler(Set<String> types) {
        return new QmsEventHandler() {
            @Override
            public Set<String> eventTypes() {
                return types;
            }

            @Override
            public void handle(ConsumerEvent event) {
            }
        };
    }
}

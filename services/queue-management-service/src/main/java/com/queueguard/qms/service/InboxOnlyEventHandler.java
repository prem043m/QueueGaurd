package com.queueguard.qms.service;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InboxOnlyEventHandler implements QmsEventHandler {
    private static final Set<String> EVENT_TYPES = Set.of(
            "QUEUE_ENTRY_REGISTERED",
            "QUEUE_ENTRY_CALLED",
            "QUEUE_ASSIGNMENT_CREATED",
            "QUEUE_ENTRY_STARTED",
            "QUEUE_ENTRY_COMPLETED",
            "QUEUE_ENTRY_NO_SHOW",
            "QUEUE_ENTRY_CANCELLED",
            "QUEUE_ENTRY_REQUEUED",
            "QUEUE_ENTRY_SKIPPED",
            "EVALUATION_SUBMITTED");

    @Override
    public Set<String> eventTypes() {
        return EVENT_TYPES;
    }

    @Override
    public void handle(ConsumerEvent event) {
        // The inbox is the only currently approved durable effect for these events.
    }
}

package com.queueguard.qms.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QmsEventHandlerRegistry {
    private final Map<String, QmsEventHandler> handlers;

    public QmsEventHandlerRegistry(List<QmsEventHandler> registeredHandlers) {
        Map<String, QmsEventHandler> mapped = new HashMap<>();
        for (QmsEventHandler handler : registeredHandlers) {
            for (String eventType : handler.eventTypes()) {
                QmsEventHandler previous = mapped.putIfAbsent(eventType, handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Multiple QMS handlers registered for event type " + eventType);
                }
            }
        }
        handlers = Map.copyOf(mapped);
    }

    public QmsEventHandler requireHandler(String eventType) {
        QmsEventHandler handler = handlers.get(eventType);
        if (handler == null) {
            throw new PermanentEventException("Unsupported event type " + eventType);
        }
        return handler;
    }
}

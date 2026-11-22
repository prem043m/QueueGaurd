package com.queueguard.qms.service;

import java.util.Set;

public interface QmsEventHandler {
    Set<String> eventTypes();

    void handle(ConsumerEvent event);
}

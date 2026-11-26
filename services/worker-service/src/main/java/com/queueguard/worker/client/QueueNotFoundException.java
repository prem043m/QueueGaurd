package com.queueguard.worker.client;

public class QueueNotFoundException extends RuntimeException {

    public QueueNotFoundException(String queue) {
        super("Configured queue does not exist: " + queue);
    }
}

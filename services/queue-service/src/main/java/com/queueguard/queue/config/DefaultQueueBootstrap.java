package com.queueguard.queue.config;

import com.queueguard.queue.repo.QueueDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "queueguard.bootstrap", name = "default-queue-enabled", havingValue = "true")
public class DefaultQueueBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueueBootstrap.class);
    private static final String DEFAULT_QUEUE_NAME = "default";

    private final QueueDefRepository queues;

    public DefaultQueueBootstrap(QueueDefRepository queues) {
        this.queues = queues;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (queues.provisionDefaultIfAbsent() > 0) {
            log.info("Provisioned local infrastructure queue '{}'", DEFAULT_QUEUE_NAME);
        }
    }
}

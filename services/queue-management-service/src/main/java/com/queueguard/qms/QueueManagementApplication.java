package com.queueguard.qms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.queueguard.common.observability.QueueGuardRequestContextFilter;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(QueueGuardRequestContextFilter.class)
public class QueueManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueManagementApplication.class, args);
    }
}

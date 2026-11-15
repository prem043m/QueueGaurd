package com.queueguard.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.queueguard")
@EnableScheduling
public class QueueApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueApplication.class, args);
    }
}

package com.queueguard.queue.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QueueClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock queueServiceClock() {
        return Clock.systemUTC();
    }
}

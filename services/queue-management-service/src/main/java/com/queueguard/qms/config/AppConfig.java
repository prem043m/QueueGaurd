package com.queueguard.qms.config;

import com.queueguard.common.security.JwtUtil;
import com.queueguard.qms.service.OutboxConsumerProperties;
import com.queueguard.qms.service.OutboxPublisherProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties({
    OutboxConsumerProperties.class,
    OutboxPublisherProperties.class
})
public class AppConfig {
    @Bean
    JwtUtil jwtUtil(@Value("${queueguard.jwt.secret}") String secret,
                    @Value("${queueguard.jwt.ttl-seconds:86400}") long ttlSeconds) {
        return new JwtUtil(secret, ttlSeconds);
    }
}

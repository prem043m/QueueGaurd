package com.queueguard.auth.config;

import com.queueguard.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${queueguard.jwt.secret}") String secret,
                           @Value("${queueguard.jwt.ttl-seconds:86400}") long ttlSeconds) {
        return new JwtUtil(secret, ttlSeconds);
    }
}

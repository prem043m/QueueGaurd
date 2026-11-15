package com.queueguard.auth.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness/readiness endpoint used by Kubernetes probes and the monitor service. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "auth-service");
    }
}

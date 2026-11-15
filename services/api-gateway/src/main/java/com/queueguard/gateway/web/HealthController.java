package com.queueguard.gateway.web;

import com.queueguard.gateway.net.ProxyClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final ProxyClient proxy;

    public HealthController(ProxyClient proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("service", "api-gateway");
        out.put("circuits", proxy.breakerStates());
        return out;
    }
}

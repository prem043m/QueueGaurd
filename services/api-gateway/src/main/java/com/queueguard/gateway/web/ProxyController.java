package com.queueguard.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.dto.ApiResponse;
import com.queueguard.gateway.entity.AuditLog;
import com.queueguard.gateway.net.AuditBuffer;
import com.queueguard.gateway.net.ProxyClient;
import com.queueguard.gateway.net.ProxyClient.CircuitOpenException;
import com.queueguard.gateway.net.ProxyClient.ProxyResponse;
import com.queueguard.gateway.net.ProxyClient.Route;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Catch-all reverse proxy. All /api/**, /monitor/** and / traffic is forwarded
 * to the owning microservice with identity + tracing headers injected.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final ProxyClient proxy;
    private final AuditBuffer audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProxyController(ProxyClient proxy, AuditBuffer audit) {
        this.proxy = proxy;
        this.audit = audit;
    }

    @RequestMapping({"/api/**", "/monitor/**", "/", "/index.html"})
    public ResponseEntity<byte[]> route(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();
        Route route = proxy.resolve(path);
        if (route == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(ApiResponse.fail("Unknown route: " + path)));
        }

        Map<String, String> inbound = new HashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(name ->
                inbound.put(name, request.getHeader(name)));

        Map<String, String> injected = new HashMap<>();
        injected.put("X-Forwarded-For", String.valueOf(request.getAttribute("qg.clientIp")));
        injected.put("X-Request-Id", String.valueOf(request.getAttribute("qg.requestId")));
        Object userId = request.getAttribute("qg.userId");
        if (userId != null) {
            injected.put("X-QG-User-Id", String.valueOf(userId));
            injected.put("X-QG-User-Email", String.valueOf(request.getAttribute("qg.userEmail")));
        }

        byte[] body = request.getInputStream().readAllBytes();
        try {
            ProxyResponse res = proxy.forward(route, request.getMethod(), request.getQueryString(), body, inbound, injected);
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.status())
                    .contentType(MediaType.parseMediaType(res.contentType()));
            res.headers().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("content-type")) {
                    builder.header(k, v);
                }
            });
            return builder.body(res.body());
        } catch (CircuitOpenException e) {
            auditFailure(request, 503, "CIRCUIT_OPEN");
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(ApiResponse.fail(
                            "Service temporarily unavailable (circuit open): " + route.service())));
        } catch (Exception e) {
            log.warn("Proxy error towards {}: {}", route.service(), e.getMessage());
            auditFailure(request, 502, "UPSTREAM_ERROR");
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(ApiResponse.fail("Upstream error: " + route.service())));
        }
    }

    private void auditFailure(HttpServletRequest request, int status, String decision) {
        AuditLog entry = new AuditLog();
        entry.setRequestId((String) request.getAttribute("qg.requestId"));
        entry.setIp((String) request.getAttribute("qg.clientIp"));
        entry.setMethod(request.getMethod());
        entry.setPath(request.getRequestURI());
        entry.setStatus(status);
        entry.setDecision(decision);
        audit.record(entry);
    }
}

package com.queueguard.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.observability.TraceContextHttpClient;
import com.queueguard.gateway.entity.AuditLog;
import com.queueguard.gateway.net.AuditBuffer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE anti-attack filter. Every request is first checked against the
 * rate-limit-service (distributed token bucket + adaptive limits + IP bans).
 *
 * If the rate-limit-service itself is unreachable, a conservative in-memory
 * fallback bucket keeps protecting the system instead of failing open.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String rateLimitUrl;
    private final AuditBuffer audit;
    private final Tracer tracer;
    private final Propagator propagator;

    private static final class Bucket {
        double tokens;
        long tsMs;
    }

    private final Map<String, Bucket> fallbackBuckets = new ConcurrentHashMap<>();
    private final double fallbackLimitPerSec;
    private final double fallbackBurst;

    public RateLimitFilter(@Value("${queueguard.svc.ratelimit}") String rateLimitUrl,
                           AuditBuffer audit,
                           @Value("${queueguard.fallback-limit-per-sec:5}") double fallbackLimitPerSec,
                           @Value("${queueguard.fallback-burst:10}") double fallbackBurst,
                           Tracer tracer,
                           Propagator propagator) {
        this.rateLimitUrl = rateLimitUrl.endsWith("/") ? rateLimitUrl.substring(0, rateLimitUrl.length() - 1) : rateLimitUrl;
        this.audit = audit;
        this.fallbackLimitPerSec = fallbackLimitPerSec;
        this.fallbackBurst = fallbackBurst;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = (String) request.getAttribute(RequestContextFilter.ATTR_CLIENT_IP);
        String path = request.getRequestURI();

        JsonNode decision = null;
        try {
            decision = remoteCheck(ip, path);
        } catch (Exception e) {
            log.debug("rate-limit-service unreachable, using local fallback: {}", e.getMessage());
        }

        if (decision != null) {
            boolean allowed = decision.path("allowed").asBoolean(true);
            double limit = decision.path("limitPerSec").asDouble(fallbackLimitPerSec);
            double remaining = decision.path("remaining").asDouble(0);
            double multiplier = decision.path("multiplier").asDouble(1.0);
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            response.setHeader("X-RateLimit-Remaining", String.valueOf((long) remaining));
            response.setHeader("X-RateLimit-Multiplier", String.valueOf(multiplier));
            response.setHeader("X-RateLimit-Mode", "distributed");
            if (!allowed) {
                long retryMs = decision.path("retryAfterMs").asLong(1000);
                boolean banned = decision.path("banned").asBoolean(false);
                String message = banned
                        ? decision.path("message").asText("IP temporarily banned")
                        : decision.path("message").asText("Rate limit exceeded");
                deny(request, response, ip, path, retryMs, message, banned ? "DENIED_BANNED" : "DENIED_RATE_LIMIT");
                return;
            }
        } else {
            boolean allowed = localFallback(ip);
            response.setHeader("X-RateLimit-Mode", "local-fallback");
            if (!allowed) {
                deny(request, response, ip, path, 1000, "Rate limit exceeded (fallback limiter)", "DENIED_RATE_LIMIT");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private JsonNode remoteCheck(String ip, String path) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("ip", ip);
        body.put("path", path);
        body.put("cost", 1);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(rateLimitUrl + "/ratelimit/check"))
                .timeout(Duration.ofMillis(1500))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> res = TraceContextHttpClient.send(
                http, request, HttpResponse.BodyHandlers.ofString(),
                tracer, propagator, "rate-limit-service");
        if (res.statusCode() != 200) {
            return null;
        }
        return mapper.readTree(res.body()).path("data");
    }

    /** Conservative token bucket kept in gateway memory (fallback mode only). */
    private boolean localFallback(String ip) {
        long now = System.currentTimeMillis();
        Bucket b = fallbackBuckets.computeIfAbsent(ip, k -> {
            Bucket nb = new Bucket();
            nb.tokens = fallbackBurst;
            nb.tsMs = now;
            return nb;
        });
        synchronized (b) {
            double deltaSec = Math.max(0, now - b.tsMs) / 1000.0;
            b.tokens = Math.min(fallbackBurst, b.tokens + deltaSec * fallbackLimitPerSec);
            b.tsMs = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void deny(HttpServletRequest request, HttpServletResponse response,
                      String ip, String path, long retryMs, String message, String decision)
            throws IOException {
        long retryAfterSec = Math.max(1, (retryMs + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(message)));

        AuditLog entry = new AuditLog();
        entry.setRequestId((String) request.getAttribute(RequestContextFilter.ATTR_REQUEST_ID));
        entry.setIp(ip);
        entry.setMethod(request.getMethod());
        entry.setPath(path);
        entry.setStatus(429);
        entry.setDecision(decision);
        audit.record(entry);
        log.warn("429 to {} on {} {} ({})", ip, request.getMethod(), path, message);
    }
}

package com.queueguard.gateway.net;

import com.queueguard.common.observability.TraceContextHttpClient;
import jakarta.annotation.PostConstruct;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes requests to downstream microservices and forwards them over HTTP,
 * with one circuit breaker per downstream service.
 */
@Component
public class ProxyClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyClient.class);
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String authUrl;
    private final String queueUrl;
    private final String workerUrl;
    private final String rateLimitUrl;
    private final String monitorUrl;
    private final String qmsUrl;
    private final Tracer tracer;
    private final Propagator propagator;

    private final Map<String, CircuitBreaker> breakers = new HashMap<>();

    public ProxyClient(@Value("${queueguard.svc.auth}") String authUrl,
                       @Value("${queueguard.svc.queue}") String queueUrl,
                       @Value("${queueguard.svc.worker}") String workerUrl,
                       @Value("${queueguard.svc.ratelimit}") String rateLimitUrl,
                       @Value("${queueguard.svc.monitor}") String monitorUrl,
                       @Value("${queueguard.svc.qms}") String qmsUrl,
                       Tracer tracer,
                       Propagator propagator) {
        this.authUrl = trim(authUrl);
        this.queueUrl = trim(queueUrl);
        this.workerUrl = trim(workerUrl);
        this.rateLimitUrl = trim(rateLimitUrl);
        this.monitorUrl = trim(monitorUrl);
        this.qmsUrl = trim(qmsUrl);
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @PostConstruct
    void init() {
        for (String svc : new String[]{"auth-service", "queue-service", "worker-service",
                "rate-limit-service", "monitor-service", "queue-management-service"}) {
            breakers.put(svc, new CircuitBreaker());
        }
    }

    public record ProxyResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
    }

    /** Maps a gateway path to the owning downstream service + rewritten path. */
    public record Route(String service, String targetUrl) {
    }

    /**
     * Routing table:
     *   /api/auth/**      -> auth-service       /auth/**
     *   /api/queues/**    -> queue-service      /queues/**
     *   /api/messages/**  -> queue-service      /messages/**
     *   /api/workers/**   -> worker-service     /workers/**
     *   /api/ratelimit/** -> rate-limit-service /ratelimit/**
     *   /api/monitor/**   -> monitor-service    /monitor/**
     *   /monitor/**       -> monitor-service    /monitor/**   (dashboard)
     *   /                 -> monitor-service    /
     */
    public Route resolve(String rawPath) {
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        if (path.equals("/") || path.equals("/index.html")) {
            return new Route("monitor-service", monitorUrl + "/");
        }
        if (path.startsWith("/monitor")) {
            return new Route("monitor-service", monitorUrl + path);
        }
        if (path.startsWith("/api")) {
            String rest = path.substring(4); // drop "/api"
            if (rest.startsWith("/auth")) return new Route("auth-service", authUrl + rest);
            if (rest.startsWith("/queues") || rest.startsWith("/messages")) return new Route("queue-service", queueUrl + rest);
            if (rest.startsWith("/workers")) return new Route("worker-service", workerUrl + rest);
            if (rest.startsWith("/ratelimit")) return new Route("rate-limit-service", rateLimitUrl + rest);
            if (rest.startsWith("/monitor")) return new Route("monitor-service", monitorUrl + rest);
            if (rest.startsWith("/v1")) return new Route("queue-management-service", qmsUrl + rest);
            return null; // unknown -> 404 handled by caller
        }
        return null;
    }

    /** Forwards one request downstream and returns the raw response. */
    public ProxyResponse forward(Route route, String method, String query,
                                 byte[] body, Map<String, String> inboundHeaders,
                                 Map<String, String> injectedHeaders) throws Exception {
        CircuitBreaker breaker = breakers.get(route.service());
        if (breaker != null && !breaker.allow()) {
            throw new CircuitOpenException(route.service());
        }

        String url = route.targetUrl() + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15));

        for (Map.Entry<String, String> h : inboundHeaders.entrySet()) {
            if (!HOP_BY_HOP.contains(h.getKey().toLowerCase())
                    && !h.getKey().equalsIgnoreCase("traceparent")) {
                builder.header(h.getKey(), h.getValue());
            }
        }
        injectedHeaders.forEach(builder::header);

        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(method, publisher);

        try {
            HttpResponse<byte[]> res = TraceContextHttpClient.send(
                    http, builder, HttpResponse.BodyHandlers.ofByteArray(),
                    tracer, propagator, route.service());
            if (breaker != null) {
                breaker.onSuccess();
            }
            Map<String, String> outHeaders = new HashMap<>();
            res.headers().firstValue("content-type").ifPresent(ct -> outHeaders.put("content-type", ct));
            res.headers().firstValue("retry-after").ifPresent(ra -> outHeaders.put("retry-after", ra));
            return new ProxyResponse(res.statusCode(),
                    outHeaders.getOrDefault("content-type", "application/json"),
                    res.body(), outHeaders);
        } catch (CircuitOpenException e) {
            throw e;
        } catch (Exception e) {
            if (breaker != null) {
                breaker.onFailure();
            }
            throw e;
        }
    }

    public Map<String, String> breakerStates() {
        Map<String, String> out = new HashMap<>();
        breakers.forEach((k, v) -> out.put(k, v.currentState().name()));
        return out;
    }

    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String service) {
            super("Circuit open for " + service);
        }
    }

    private static String trim(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

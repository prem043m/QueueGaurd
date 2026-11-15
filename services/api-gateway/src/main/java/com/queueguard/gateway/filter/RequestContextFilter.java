package com.queueguard.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a request id and resolves the true client IP.
 *
 * SECURITY: X-Forwarded-For is only honoured when queueguard.trust-proxy-headers
 * is enabled (i.e. the gateway sits behind a trusted Layer-7 proxy/ingress that
 * overwrites the header). Otherwise a naive attacker could spoof rotating
 * X-Forwarded-For values to dodge per-IP rate limiting.
 */
@Component("qgRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String ATTR_REQUEST_ID = "qg.requestId";
    public static final String ATTR_CLIENT_IP = "qg.clientIp";
    public static final String ATTR_USER_ID = "qg.userId";
    public static final String ATTR_USER_EMAIL = "qg.userEmail";

    private final boolean trustProxyHeaders;

    public RequestContextFilter(@Value("${queueguard.trust-proxy-headers:false}") boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        request.setAttribute(ATTR_CLIENT_IP, clientIp(request));
        response.setHeader("X-Request-Id", requestId);
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty() && first.length() <= 64) {
                    return first;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank() && realIp.length() <= 64) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}

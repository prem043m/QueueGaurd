package com.queueguard.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.security.JwtUtil;
import com.queueguard.gateway.entity.AuditLog;
import com.queueguard.gateway.net.AuditBuffer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** Enforces JWT authentication on all /api routes except the public ones. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/auth/register", "/api/auth/login", "/api/auth/refresh");

    private final JwtUtil jwt;
    private final AuditBuffer audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtAuthFilter(JwtUtil jwt, AuditBuffer audit) {
        this.jwt = jwt;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (PUBLIC_PREFIXES.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            deny(request, response, "Missing bearer token");
            return;
        }
        try {
            JwtUtil.JwtClaims claims = jwt.validate(auth.substring(7));
            request.setAttribute(RequestContextFilter.ATTR_USER_ID,
                    claims.raw().path("uid").asText(claims.subject()));
            request.setAttribute(RequestContextFilter.ATTR_USER_EMAIL,
                    claims.email() != null ? claims.email() : claims.subject());
            chain.doFilter(request, response);
        } catch (IllegalArgumentException e) {
            deny(request, response, "Invalid token: " + e.getMessage());
        }
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(message)));

        AuditLog entry = new AuditLog();
        entry.setRequestId((String) request.getAttribute(RequestContextFilter.ATTR_REQUEST_ID));
        entry.setIp((String) request.getAttribute(RequestContextFilter.ATTR_CLIENT_IP));
        entry.setMethod(request.getMethod());
        entry.setPath(request.getRequestURI());
        entry.setStatus(401);
        entry.setDecision("DENIED_AUTH");
        audit.record(entry);
    }
}

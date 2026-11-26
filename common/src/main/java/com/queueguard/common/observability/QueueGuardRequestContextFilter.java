package com.queueguard.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class QueueGuardRequestContextFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_ATTRIBUTE = "qg.requestId";
    private static final String CORRELATION_ID = "correlationId";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            Object existingId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
            requestId = existingId instanceof String id ? id : null;
        }
        if (requestId == null || requestId.isBlank()
                || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID, requestId);
        if (response.getHeader("X-Request-Id") == null) {
            response.setHeader("X-Request-Id", requestId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }
}

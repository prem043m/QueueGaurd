package com.queueguard.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class QueueGuardRequestContextFilterTest {
    private final QueueGuardRequestContextFilter filter =
            new QueueGuardRequestContextFilter();

    @Test
    void propagatesRequestIdToMdcAndClearsItAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                assertEquals("request-42", MDC.get("correlationId"));

        filter.doFilter(request, response, chain);

        assertEquals("request-42", response.getHeader("X-Request-Id"));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void reusesGatewayRequestAttributeWhenNoHeaderIsAvailable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("qg.requestId", "gateway-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertEquals("gateway-request", MDC.get("correlationId")));

        assertEquals("gateway-request", response.getHeader("X-Request-Id"));
        assertNull(MDC.get("correlationId"));
    }
}

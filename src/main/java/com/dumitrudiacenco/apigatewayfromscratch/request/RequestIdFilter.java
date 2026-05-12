package com.dumitrudiacenco.apigatewayfromscratch.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_CLIENT_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        request.setAttribute(GatewayRequestAttributes.REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!response.isCommitted() && response.getHeader(GatewayRequestAttributes.REQUEST_ID_HEADER) == null) {
                response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, requestId);
            }
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String raw = request.getHeader(GatewayRequestAttributes.REQUEST_ID_HEADER);
        if (raw != null) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > MAX_CLIENT_ID_LENGTH
                        ? trimmed.substring(0, MAX_CLIENT_ID_LENGTH)
                        : trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}

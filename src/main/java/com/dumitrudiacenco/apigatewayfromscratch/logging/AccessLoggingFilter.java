package com.dumitrudiacenco.apigatewayfromscratch.logging;

import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAtNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            Object requestId = request.getAttribute(GatewayRequestAttributes.REQUEST_ID);
            String normalizedRequestId = requestId instanceof String value ? value : "";
            log.info(
                    "access requestId={} method={} path={} status={} durationMs={}",
                    normalizedRequestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs
            );
        }
    }
}

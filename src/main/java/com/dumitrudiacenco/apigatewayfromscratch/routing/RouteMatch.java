package com.dumitrudiacenco.apigatewayfromscratch.routing;

import java.net.URI;

public record RouteMatch(
        URI upstreamUri,
        int retryAttempts,
        int circuitBreakerFailureThreshold,
        long circuitBreakerOpenWaitMillis,
        String routeKey,
        int rateLimitBurst,
        long rateLimitRefillMillis,
        String jwtHs256Secret
) {}

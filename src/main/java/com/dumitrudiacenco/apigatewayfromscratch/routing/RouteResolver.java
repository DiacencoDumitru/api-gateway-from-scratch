package com.dumitrudiacenco.apigatewayfromscratch.routing;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import com.dumitrudiacenco.apigatewayfromscratch.config.GatewayRoutingProperties.Route;

public final class RouteResolver {

    private final List<ResolvedRoute> resolvedRoutes;

    public RouteResolver(List<Route> routes) {
        List<ResolvedRoute> list = new ArrayList<>();
        if (routes != null) {
            for (Route route : routes) {
                if (route == null || route.getPathPrefix() == null || route.getPathPrefix().isBlank()
                        || route.getTargetBaseUrl() == null || route.getTargetBaseUrl().isBlank()) {
                    continue;
                }
                String prefix = normalizePrefix(route.getPathPrefix());
                String base = stripTrailingSlashes(route.getTargetBaseUrl());
                int retryAttempts = route.getRetryAttempts() != null && route.getRetryAttempts() > 0 ? route.getRetryAttempts() : 1;
                int failureThreshold =
                        route.getCircuitBreakerFailureThreshold() != null && route.getCircuitBreakerFailureThreshold() > 0
                                ? route.getCircuitBreakerFailureThreshold()
                                : 0;
                long openWaitMillis =
                        route.getCircuitBreakerOpenWaitMillis() != null && route.getCircuitBreakerOpenWaitMillis() > 0
                                ? route.getCircuitBreakerOpenWaitMillis()
                                : 0L;
                int rateLimitBurst =
                        route.getRateLimitBurst() != null && route.getRateLimitBurst() > 0 ? route.getRateLimitBurst() : 0;
                long rateLimitRefillMillis =
                        route.getRateLimitRefillMillis() != null && route.getRateLimitRefillMillis() > 0
                                ? route.getRateLimitRefillMillis()
                                : 0L;
                String routeKey = prefix + "|" + base;
                list.add(new ResolvedRoute(
                        prefix,
                        base,
                        retryAttempts,
                        failureThreshold,
                        openWaitMillis,
                        routeKey,
                        rateLimitBurst,
                        rateLimitRefillMillis));
            }
        }
        list.sort(Comparator.comparingInt(r -> -r.pathPrefix().length()));
        this.resolvedRoutes = List.copyOf(list);
    }

    public Optional<RouteMatch> resolve(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }
        if (!requestPath.startsWith("/")) {
            requestPath = "/" + requestPath;
        }
        for (ResolvedRoute route : resolvedRoutes) {
            Optional<String> remainder = matchRemainder(route.pathPrefix(), requestPath);
            if (remainder.isPresent()) {
                return Optional.of(new RouteMatch(
                        buildUpstreamUri(route.targetBaseUrl(), remainder.get()),
                        route.retryAttempts(),
                        route.circuitBreakerFailureThreshold(),
                        route.circuitBreakerOpenWaitMillis(),
                        route.routeKey(),
                        route.rateLimitBurst(),
                        route.rateLimitRefillMillis()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> matchRemainder(String prefix, String path) {
        if (prefix.equals("/")) {
            return Optional.of(path);
        }
        if (path.equals(prefix)) {
            return Optional.of("");
        }
        if (path.startsWith(prefix + "/")) {
            return Optional.of(path.substring(prefix.length()));
        }
        return Optional.empty();
    }

    private static URI buildUpstreamUri(String targetBaseUrl, String remainder) {
        String pathPart = remainder.isEmpty() || remainder.equals("/") ? "/" : remainder;
        if (!pathPart.startsWith("/")) {
            pathPart = "/" + pathPart;
        }
        return URI.create(targetBaseUrl + pathPart);
    }

    private static String normalizePrefix(String pathPrefix) {
        String p = pathPrefix.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String stripTrailingSlashes(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private record ResolvedRoute(
            String pathPrefix,
            String targetBaseUrl,
            int retryAttempts,
            int circuitBreakerFailureThreshold,
            long circuitBreakerOpenWaitMillis,
            String routeKey,
            int rateLimitBurst,
            long rateLimitRefillMillis
    ) {}
}

package com.dumitrudiacenco.apigatewayfromscratch.proxy;

import com.dumitrudiacenco.apigatewayfromscratch.config.GatewayUpstreamRestClientConfiguration;
import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import com.dumitrudiacenco.apigatewayfromscratch.resilience.RouteCircuitBreakers;
import com.dumitrudiacenco.apigatewayfromscratch.ratelimit.RouteTokenBuckets;
import com.dumitrudiacenco.apigatewayfromscratch.ratelimit.RouteTokenBuckets.TryResult;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RefreshableRouteResolver;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RouteMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class UpstreamProxyFilter extends OncePerRequestFilter {

    private static final Set<String> HOP_REQUEST =
            Set.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                    "host",
                    "content-length",
                    "x-request-id");

    private static final Set<String> HOP_RESPONSE =
            Set.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                    "x-request-id");

    private final RestClient restClient;
    private final RefreshableRouteResolver routeResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RouteCircuitBreakers circuitBreakers;
    private final RouteTokenBuckets routeTokenBuckets;

    public UpstreamProxyFilter(
            @Qualifier(GatewayUpstreamRestClientConfiguration.GATEWAY_UPSTREAM_REST_CLIENT) RestClient restClient,
            RefreshableRouteResolver routeResolver,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RouteCircuitBreakers circuitBreakers,
            RouteTokenBuckets routeTokenBuckets) {
        this.restClient = restClient;
        this.routeResolver = routeResolver;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.circuitBreakers = circuitBreakers;
        this.routeTokenBuckets = routeTokenBuckets;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = requestPath(request);
        var match = routeResolver.resolve(path);
        if (match.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        RouteMatch routeMatch = match.get();
        URI upstream = withQuery(routeMatch.upstreamUri(), request.getQueryString());
        proxy(request, response, upstream, routeMatch);
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.isEmpty()) {
            return "/";
        }
        return uri;
    }

    private static URI withQuery(URI upstream, String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return upstream;
        }
        return URI.create(upstream.toASCIIString() + "?" + rawQuery);
    }

    private void proxy(HttpServletRequest request, HttpServletResponse response, URI upstream, RouteMatch routeMatch)
            throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        Object requestId = request.getAttribute(GatewayRequestAttributes.REQUEST_ID);
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        String routeKey = routeMatch.routeKey();
        int failureThreshold = routeMatch.circuitBreakerFailureThreshold();
        long openWaitMillis = routeMatch.circuitBreakerOpenWaitMillis();

        String bucketKey = routeKey + "|" + clientKey(request);
        TryResult rateLimit = routeTokenBuckets.tryConsume(
                bucketKey,
                routeMatch.rateLimitBurst(),
                routeMatch.rateLimitRefillMillis());
        if (!rateLimit.allowed()) {
            writeRateLimited(response, requestId, rateLimit.retryAfterSeconds());
            recordProxyMetrics(method, HttpStatus.TOO_MANY_REQUESTS.value(), "rate_limited");
            sample.stop(Timer.builder("gateway.proxy.request.duration")
                    .tag("method", method.name())
                    .register(meterRegistry));
            return;
        }

        if (!circuitBreakers.allowRequest(routeKey, failureThreshold, openWaitMillis)) {
            writeCircuitOpen(response, requestId, openWaitMillis);
            recordProxyMetrics(method, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "circuit_open");
            sample.stop(Timer.builder("gateway.proxy.request.duration")
                    .tag("method", method.name())
                    .register(meterRegistry));
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();

        var uriSpec = restClient.method(method).uri(upstream);
        var headersSpec = uriSpec.headers(headers -> copyRequestHeaders(request, headers, upstream));

        int retryAttempts = routeMatch.retryAttempts();
        int attempts = shouldRetry(method) ? Math.max(1, retryAttempts) : 1;
        RestClientException lastFailure = null;
        ResponseEntity<byte[]> entity = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (sendsBody(method) && body.length > 0) {
                    entity = headersSpec.body(body).exchange((req, res) -> readEntity(res));
                } else {
                    entity = headersSpec.exchange((req, res) -> readEntity(res));
                }
                if (entity.getStatusCode().is5xxServerError() && attempt < attempts) {
                    continue;
                }
                break;
            } catch (RestClientException ex) {
                lastFailure = ex;
                if (attempt == attempts) {
                    break;
                }
            }
        }
        try {
            if (entity != null) {
                writeEntityResponse(response, entity, requestId);
                recordProxyMetrics(method, entity.getStatusCode().value(), "upstream");
                if (entity.getStatusCode().is5xxServerError()) {
                    circuitBreakers.recordFailure(routeKey, failureThreshold, openWaitMillis);
                } else {
                    circuitBreakers.recordSuccess(routeKey, failureThreshold);
                }
            } else if (lastFailure != null) {
                writeUpstreamUnreachable(response, requestId);
                recordProxyMetrics(method, HttpServletResponse.SC_BAD_GATEWAY, "unreachable");
                circuitBreakers.recordFailure(routeKey, failureThreshold, openWaitMillis);
            }
        } finally {
            sample.stop(Timer.builder("gateway.proxy.request.duration")
                    .tag("method", method.name())
                    .register(meterRegistry));
        }
    }

    private void recordProxyMetrics(HttpMethod method, int statusCode, String outcome) {
        meterRegistry.counter(
                "gateway.proxy.requests",
                "method", method.name(),
                "status", Integer.toString(statusCode),
                "outcome", outcome
        ).increment();
    }

    private static ResponseEntity<byte[]> readEntity(ClientHttpResponse res) throws IOException {
        try (res) {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(res.getHeaders());
            byte[] responseBody = res.getBody().readAllBytes();
            return ResponseEntity.status(res.getStatusCode()).headers(headers).body(responseBody);
        }
    }

    private void writeEntityResponse(HttpServletResponse response, ResponseEntity<byte[]> entity, Object requestId)
            throws IOException {
        response.setStatus(entity.getStatusCode().value());
        entity.getHeaders().forEach((name, values) -> {
            if (name == null || shouldStripResponseHeader(name)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        byte[] responseBody = entity.getBody();
        if (responseBody != null && responseBody.length > 0) {
            response.getOutputStream().write(responseBody);
        }

        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }
    }

    private void writeUpstreamUnreachable(HttpServletResponse response, Object requestId) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", HttpServletResponse.SC_BAD_GATEWAY);
        root.put("error", "upstream_unreachable");
        if (requestId instanceof String id && !id.isBlank()) {
            root.put("requestId", id);
        }
        byte[] bytes = objectMapper.writeValueAsBytes(root);
        response.getOutputStream().write(bytes);
    }

    private void writeCircuitOpen(HttpServletResponse response, Object requestId, long openWaitMillis) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        long retryAfterSeconds = Math.max(1L, (openWaitMillis + 999L) / 1000L);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        root.put("error", "circuit_open");
        root.put("retryAfterSeconds", retryAfterSeconds);
        if (requestId instanceof String id && !id.isBlank()) {
            root.put("requestId", id);
        }
        byte[] bytes = objectMapper.writeValueAsBytes(root);
        response.getOutputStream().write(bytes);
    }

    private void writeRateLimited(HttpServletResponse response, Object requestId, long retryAfterSeconds) throws IOException {
        int status = HttpStatus.TOO_MANY_REQUESTS.value();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", status);
        root.put("error", "rate_limited");
        root.put("retryAfterSeconds", retryAfterSeconds);
        if (requestId instanceof String id && !id.isBlank()) {
            root.put("requestId", id);
        }
        byte[] bytes = objectMapper.writeValueAsBytes(root);
        response.getOutputStream().write(bytes);
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma < 0 ? forwarded.trim() : forwarded.substring(0, comma).trim();
            return first.isEmpty() ? "unknown" : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static boolean sendsBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private static boolean shouldRetry(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpHeaders target, URI upstreamUri) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || shouldStripRequestHeader(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            if (values == null) {
                continue;
            }
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                if (value != null) {
                    target.add(name, value);
                }
            }
        }
        target.put(HttpHeaders.HOST, Collections.singletonList(hostHeader(upstreamUri)));
        Object requestId = request.getAttribute(GatewayRequestAttributes.REQUEST_ID);
        if (requestId instanceof String id && !id.isBlank()) {
            target.put(GatewayRequestAttributes.REQUEST_ID_HEADER, Collections.singletonList(id));
        }
    }

    private static String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        int port = uri.getPort();
        if (port < 0) {
            return host;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && port == 80) {
            return host;
        }
        if ("https".equalsIgnoreCase(uri.getScheme()) && port == 443) {
            return host;
        }
        return host + ":" + port;
    }

    private static boolean shouldStripRequestHeader(String name) {
        return HOP_REQUEST.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean shouldStripResponseHeader(String name) {
        return HOP_RESPONSE.contains(name.toLowerCase(Locale.ROOT));
    }
}

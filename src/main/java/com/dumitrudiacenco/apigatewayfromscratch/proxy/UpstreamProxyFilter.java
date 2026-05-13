package com.dumitrudiacenco.apigatewayfromscratch.proxy;

import com.dumitrudiacenco.apigatewayfromscratch.config.GatewayUpstreamRestClientConfiguration;
import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RouteResolver;
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
    private final RouteResolver routeResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public UpstreamProxyFilter(
            @Qualifier(GatewayUpstreamRestClientConfiguration.GATEWAY_UPSTREAM_REST_CLIENT) RestClient restClient,
            RouteResolver routeResolver,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.routeResolver = routeResolver;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
        URI upstream = withQuery(match.get().upstreamUri(), request.getQueryString());
        proxy(request, response, upstream);
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

    private void proxy(HttpServletRequest request, HttpServletResponse response, URI upstream) throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        Object requestId = request.getAttribute(GatewayRequestAttributes.REQUEST_ID);
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = request.getInputStream().readAllBytes();

        var uriSpec = restClient.method(method).uri(upstream);
        var headersSpec = uriSpec.headers(headers -> copyRequestHeaders(request, headers, upstream));

        try {
            ResponseEntity<byte[]> entity;
            if (sendsBody(method) && body.length > 0) {
                entity = headersSpec.body(body).exchange((req, res) -> readEntity(res));
            } else {
                entity = headersSpec.exchange((req, res) -> readEntity(res));
            }
            writeEntityResponse(response, entity, requestId);
            recordProxyMetrics(method, entity.getStatusCode().value(), "upstream");
        } catch (RestClientException e) {
            writeUpstreamUnreachable(response, requestId);
            recordProxyMetrics(method, HttpServletResponse.SC_BAD_GATEWAY, "unreachable");
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

    private static boolean sendsBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
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

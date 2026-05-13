package com.dumitrudiacenco.apigatewayfromscratch.auth;

import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RefreshableRouteResolver;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RouteMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 45)
public class JwtRouteAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "bearer ";

    private final RefreshableRouteResolver routeResolver;
    private final ObjectMapper objectMapper;

    public JwtRouteAuthFilter(RefreshableRouteResolver routeResolver, ObjectMapper objectMapper) {
        this.routeResolver = routeResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<RouteMatch> match = routeResolver.resolve(requestPath(request));
        if (match.isPresent()) {
            String secret = match.get().jwtHs256Secret();
            if (secret != null && !secret.isBlank()) {
                if (!isAuthorized(request, secret)) {
                    writeUnauthorized(response, request.getAttribute(GatewayRequestAttributes.REQUEST_ID));
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
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

    private static boolean isAuthorized(HttpServletRequest request, String secret) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return false;
        }
        String value = header.trim();
        if (value.length() < BEARER_PREFIX.length()) {
            return false;
        }
        if (!value.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return false;
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return false;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return false;
            }
            MACVerifier verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
            if (!jwt.verify(verifier)) {
                return false;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                return false;
            }
            return true;
        } catch (ParseException | JOSEException ex) {
            return false;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, Object requestId) throws IOException {
        int status = HttpStatus.UNAUTHORIZED.value();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (requestId instanceof String id && !id.isBlank()) {
            response.setHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, id);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", status);
        root.put("error", "unauthorized");
        if (requestId instanceof String id && !id.isBlank()) {
            root.put("requestId", id);
        }
        response.getOutputStream().write(objectMapper.writeValueAsBytes(root));
    }
}

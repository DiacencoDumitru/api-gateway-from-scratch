package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.dumitrudiacenco.apigatewayfromscratch.ratelimit.RouteTokenBuckets;
import com.dumitrudiacenco.apigatewayfromscratch.resilience.RouteCircuitBreakers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtRouteAuthIT {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("gateway.routing.routes[0].jwt-hs256-secret", () -> SECRET);
        registry.add("gateway.upstream.http.read-timeout", () -> "2s");
        registry.add("gateway.upstream.http.connect-timeout", () -> "2s");
    }

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RouteCircuitBreakers circuitBreakers;

    @Autowired
    RouteTokenBuckets routeTokenBuckets;

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
        circuitBreakers.resetAll();
        routeTokenBuckets.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void shouldRejectMissingBearerToken() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/jwt-protected")).willReturn(aResponse().withStatus(200).withBody("ok")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = exchangeGet(client, "/api/jwt-protected");

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("error").asText()).isEqualTo("unauthorized");
        WIRE_MOCK.verify(0, getRequestedFor(urlEqualTo("/jwt-protected")));
    }

    @Test
    void shouldForwardWhenBearerTokenValid() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/jwt-protected")).willReturn(aResponse().withStatus(200).withBody("ok")));

        String token = mintToken();
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = client.get()
                .uri("/api/jwt-protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange((request, res) -> {
                    try (res) {
                        HttpHeaders headers = new HttpHeaders();
                        headers.addAll(res.getHeaders());
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return ResponseEntity.status(res.getStatusCode()).headers(headers).body(body);
                    }
                });

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("ok");
        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo("/jwt-protected")));
    }

    private static String mintToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .expirationTime(new Date(System.currentTimeMillis() + 120_000L))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static ResponseEntity<String> exchangeGet(RestClient client, String path) {
        return client.get()
                .uri(path)
                .exchange((request, res) -> {
                    try (res) {
                        HttpHeaders headers = new HttpHeaders();
                        headers.addAll(res.getHeaders());
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return ResponseEntity.status(res.getStatusCode()).headers(headers).body(body);
                    }
                });
    }
}

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitTokenBucketIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("gateway.routing.routes[0].rate-limit-burst", () -> "2");
        registry.add("gateway.routing.routes[0].rate-limit-refill-millis", () -> "60000");
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
    void shouldRejectExcessRequestsWithoutCallingUpstream() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/rl-ok")).willReturn(aResponse().withStatus(200).withBody("ok")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();

        ResponseEntity<String> first = exchangeGet(client, "/api/rl-ok");
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = exchangeGet(client, "/api/rl-ok");
        assertThat(second.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> third = exchangeGet(client, "/api/rl-ok");
        assertThat(third.getStatusCode().value()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(third.getBody());
        assertThat(body.path("error").asText()).isEqualTo("rate_limited");

        WIRE_MOCK.verify(2, getRequestedFor(urlEqualTo("/rl-ok")));
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

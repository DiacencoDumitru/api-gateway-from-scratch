package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CircuitBreakerIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("gateway.routing.routes[0].circuit-breaker-failure-threshold", () -> "2");
        registry.add("gateway.routing.routes[0].circuit-breaker-open-wait-millis", () -> "500");
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

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
        circuitBreakers.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void shouldOpenCircuitAfterRepeatedUpstreamFailures() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/cb-down")).willReturn(aResponse().withStatus(500).withBody("err")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();

        ResponseEntity<String> first = client.get().uri("/api/cb-down").retrieve().toEntity(String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(500);

        ResponseEntity<String> second = client.get().uri("/api/cb-down").retrieve().toEntity(String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(500);

        ResponseEntity<String> third = client.get().uri("/api/cb-down").retrieve().toEntity(String.class);
        assertThat(third.getStatusCode().value()).isEqualTo(503);
        JsonNode body = objectMapper.readTree(third.getBody());
        assertThat(body.path("error").asText()).isEqualTo("circuit_open");

        WIRE_MOCK.verify(2, getRequestedFor(urlEqualTo("/cb-down")));
    }
}

package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

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
class ProxyMetricsIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("management.endpoints.web.exposure.include", () -> "health,metrics");
    }

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void shouldExposeGatewayProxyMetrics() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/metrics-ok")).willReturn(aResponse().withStatus(200).withBody("ok")));
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();

        ResponseEntity<String> proxied = client.get().uri("/api/metrics-ok").retrieve().toEntity(String.class);
        assertThat(proxied.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> metricResponse = client.get().uri("/actuator/metrics/gateway.proxy.requests").retrieve().toEntity(String.class);
        assertThat(metricResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(metricResponse.getBody());
        assertThat(body.path("name").asText()).isEqualTo("gateway.proxy.requests");
        assertThat(body.path("measurements").isArray()).isTrue();
        assertThat(body.path("measurements").isEmpty()).isFalse();
    }
}

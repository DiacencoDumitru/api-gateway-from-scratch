package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.inScenario;
import static com.github.tomakehurst.wiremock.client.WireMock.WIREMOCK_SCENARIO_STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.willSetStateTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpstreamProxyIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("gateway.routing.routes[0].retry-attempts", () -> "2");
        registry.add("gateway.routing.routes[1].path-prefix", () -> "/dead");
        registry.add("gateway.routing.routes[1].target-base-url", () -> "http://127.0.0.1:1");
        registry.add("gateway.upstream.http.read-timeout", () -> "1s");
        registry.add("gateway.upstream.http.connect-timeout", () -> "2s");
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
    void getForwardsToUpstream() {
        WIRE_MOCK.stubFor(
                get(urlEqualTo("/hello")).willReturn(aResponse().withStatus(200).withBody("world")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response =
                client.get().uri("/api/hello").retrieve().toEntity(String.class);

        assertThat(response.getBody()).isEqualTo("world");
        String requestId = response.getHeaders().getFirst(GatewayRequestAttributes.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/hello")).withHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, equalTo(requestId)));
    }

    @Test
    void getUsesClientRequestIdForUpstreamAndResponse() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        String clientId = "client-req-1";
        ResponseEntity<String> response = client.get()
                .uri("/api/ping")
                .header(GatewayRequestAttributes.REQUEST_ID_HEADER, clientId)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getBody()).isEqualTo("pong");
        assertThat(response.getHeaders().getFirst(GatewayRequestAttributes.REQUEST_ID_HEADER)).isEqualTo(clientId);
        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/ping")).withHeader(GatewayRequestAttributes.REQUEST_ID_HEADER, equalTo(clientId)));
    }

    @Test
    void getForwardsUpstream503WithBody() {
        WIRE_MOCK.stubFor(
                get(urlEqualTo("/down")).willReturn(aResponse().withStatus(503).withBody("maintenance")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = exchangeGet(client, "/api/down");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo("maintenance");
    }

    @Test
    void getReturns502JsonWhenUpstreamReadTimesOut() throws Exception {
        WIRE_MOCK.stubFor(
                get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = exchangeGet(client, "/api/slow");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        JsonNode node = objectMapper.readTree(response.getBody());
        assertThat(node.path("status").asInt()).isEqualTo(502);
        assertThat(node.path("error").asText()).isEqualTo("upstream_unreachable");
        assertThat(node.path("requestId").asText()).isNotBlank();
    }

    @Test
    void getReturns502JsonWhenUpstreamUnreachable() throws Exception {
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = exchangeGet(client, "/dead/any");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        JsonNode node = objectMapper.readTree(response.getBody());
        assertThat(node.path("status").asInt()).isEqualTo(502);
        assertThat(node.path("error").asText()).isEqualTo("upstream_unreachable");
        assertThat(node.path("requestId").asText()).isNotBlank();
        assertThat(response.getHeaders().getFirst(GatewayRequestAttributes.REQUEST_ID_HEADER))
                .isEqualTo(node.path("requestId").asText());
    }

    @Test
    void postForwardsBodyToUpstream() {
        WIRE_MOCK.stubFor(
                post(urlEqualTo("/echo"))
                        .withRequestBody(equalTo("payload"))
                        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", MediaType.TEXT_PLAIN_VALUE).withBody("echoed")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        String body = client.post().uri("/api/echo").contentType(MediaType.TEXT_PLAIN).body("payload").retrieve().body(String.class);

        assertThat(body).isEqualTo("echoed");
    }

    @Test
    void getRetriesAndSucceedsOnSecondAttempt() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/flaky"))
                .inScenario("retry-flow")
                .whenScenarioStateIs(WIREMOCK_SCENARIO_STARTED)
                .willReturn(aResponse().withStatus(503).withBody("temporary"))
                .willSetStateTo("recovered"));
        WIRE_MOCK.stubFor(get(urlEqualTo("/flaky"))
                .inScenario("retry-flow")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("ok-after-retry")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        ResponseEntity<String> response = exchangeGet(client, "/api/flaky");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("ok-after-retry");
        WIRE_MOCK.verify(2, getRequestedFor(urlEqualTo("/flaky")));
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

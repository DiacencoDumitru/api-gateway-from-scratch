package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.dumitrudiacenco.apigatewayfromscratch.request.GatewayRequestAttributes;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
    }

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder restClientBuilder;

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
    void postForwardsBodyToUpstream() {
        WIRE_MOCK.stubFor(
                post(urlEqualTo("/echo"))
                        .withRequestBody(equalTo("payload"))
                        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", MediaType.TEXT_PLAIN_VALUE).withBody("echoed")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        String body = client.post().uri("/api/echo").contentType(MediaType.TEXT_PLAIN).body("payload").retrieve().body(String.class);

        assertThat(body).isEqualTo("echoed");
    }
}

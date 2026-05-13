package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.dumitrudiacenco.apigatewayfromscratch.config.GatewayRoutingProperties;
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
class DynamicRoutesReloadIT {

    private static final WireMockServer UPSTREAM_A = new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer UPSTREAM_B = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM_A.start();
        UPSTREAM_B.start();
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routing.routes[0].path-prefix", () -> "/api");
        registry.add("gateway.routing.routes[0].target-base-url", () -> "http://localhost:" + UPSTREAM_A.port());
        registry.add("gateway.upstream.http.read-timeout", () -> "2s");
        registry.add("gateway.upstream.http.connect-timeout", () -> "2s");
    }

    @LocalServerPort
    int port;

    @Autowired
    GatewayRoutingProperties gatewayRoutingProperties;

    @Autowired
    RestClient.Builder restClientBuilder;

    @BeforeEach
    void resetWireMock() {
        UPSTREAM_A.resetAll();
        UPSTREAM_B.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        UPSTREAM_A.stop();
        UPSTREAM_B.stop();
    }

    @Test
    void reloadPicksUpChangedTargetBaseUrl() {
        UPSTREAM_A.stubFor(get(urlEqualTo("/hello")).willReturn(aResponse().withStatus(200).withBody("from-a")));
        UPSTREAM_B.stubFor(get(urlEqualTo("/hello")).willReturn(aResponse().withStatus(200).withBody("from-b")));

        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        assertThat(client.get().uri("/api/hello").retrieve().body(String.class)).isEqualTo("from-a");

        gatewayRoutingProperties.getRoutes().getFirst().setTargetBaseUrl("http://localhost:" + UPSTREAM_B.port());
        assertThat(client.get().uri("/api/hello").retrieve().body(String.class)).isEqualTo("from-a");

        ResponseEntity<Void> reload = client.post().uri("/actuator/gatewayroutes").retrieve().toBodilessEntity();
        assertThat(reload.getStatusCode().value()).isEqualTo(204);

        assertThat(client.get().uri("/api/hello").retrieve().body(String.class)).isEqualTo("from-b");
    }
}

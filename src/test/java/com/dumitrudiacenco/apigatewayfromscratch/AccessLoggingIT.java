package com.dumitrudiacenco.apigatewayfromscratch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLoggingIT {

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
    void shouldWriteStructuredAccessLog(CapturedOutput output) {
        WIRE_MOCK.stubFor(get(urlEqualTo("/hello")).willReturn(aResponse().withStatus(200).withBody("world")));
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();

        ResponseEntity<String> response = client.get().uri("/api/hello").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String logs = output.getOut();
        assertThat(logs).contains("access requestId=");
        assertThat(logs).contains("method=GET");
        assertThat(logs).contains("path=/api/hello");
        assertThat(logs).contains("status=200");
        assertThat(logs).contains("durationMs=");
    }
}

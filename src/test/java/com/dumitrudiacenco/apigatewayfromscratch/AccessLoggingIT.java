package com.dumitrudiacenco.apigatewayfromscratch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLoggingIT {

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Test
    void shouldWriteStructuredAccessLog(CapturedOutput output) {
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();

        ResponseEntity<String> response = client.get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String logs = output.getOut();
        assertThat(logs).contains("access requestId=");
        assertThat(logs).contains("method=GET");
        assertThat(logs).contains("path=/actuator/health");
        assertThat(logs).contains("status=200");
        assertThat(logs).contains("durationMs=");
    }
}

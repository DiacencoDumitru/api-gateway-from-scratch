package com.dumitrudiacenco.apigatewayfromscratch.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayUpstreamHttpProperties.class)
public class GatewayUpstreamRestClientConfiguration {

    public static final String GATEWAY_UPSTREAM_REST_CLIENT = "gatewayUpstream";

    @Bean(name = GATEWAY_UPSTREAM_REST_CLIENT)
    RestClient gatewayUpstreamRestClient(GatewayUpstreamHttpProperties properties) {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}

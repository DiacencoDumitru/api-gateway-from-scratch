package com.dumitrudiacenco.apigatewayfromscratch.config;

import com.dumitrudiacenco.apigatewayfromscratch.actuator.GatewayRoutesEndpoint;
import com.dumitrudiacenco.apigatewayfromscratch.routing.RefreshableRouteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayRoutingProperties.class)
public class GatewayRoutingConfiguration {

    @Bean
    GatewayRoutesEndpoint gatewayRoutesEndpoint(RefreshableRouteResolver refreshableRouteResolver) {
        return new GatewayRoutesEndpoint(refreshableRouteResolver);
    }
}

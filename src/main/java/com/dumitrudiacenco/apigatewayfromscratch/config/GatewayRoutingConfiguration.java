package com.dumitrudiacenco.apigatewayfromscratch.config;

import com.dumitrudiacenco.apigatewayfromscratch.routing.RouteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayRoutingProperties.class)
public class GatewayRoutingConfiguration {

    @Bean
    RouteResolver routeResolver(GatewayRoutingProperties properties) {
        return new RouteResolver(properties.getRoutes());
    }
}

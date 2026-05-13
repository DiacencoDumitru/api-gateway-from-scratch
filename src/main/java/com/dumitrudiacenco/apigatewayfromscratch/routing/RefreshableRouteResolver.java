package com.dumitrudiacenco.apigatewayfromscratch.routing;

import com.dumitrudiacenco.apigatewayfromscratch.config.GatewayRoutingProperties;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RefreshableRouteResolver {

    private final GatewayRoutingProperties properties;
    private final AtomicReference<RouteResolver> resolver;

    public RefreshableRouteResolver(GatewayRoutingProperties properties) {
        this.properties = properties;
        this.resolver = new AtomicReference<>(new RouteResolver(properties.getRoutes()));
    }

    public Optional<RouteMatch> resolve(String requestPath) {
        return resolver.get().resolve(requestPath);
    }

    public synchronized void reload() {
        resolver.set(new RouteResolver(properties.getRoutes()));
    }
}

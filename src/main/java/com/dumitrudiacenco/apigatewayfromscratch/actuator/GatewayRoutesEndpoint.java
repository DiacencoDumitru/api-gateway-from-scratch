package com.dumitrudiacenco.apigatewayfromscratch.actuator;

import com.dumitrudiacenco.apigatewayfromscratch.routing.RefreshableRouteResolver;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

@Endpoint(id = "gatewayroutes")
public class GatewayRoutesEndpoint {

    private final RefreshableRouteResolver refreshableRouteResolver;

    public GatewayRoutesEndpoint(RefreshableRouteResolver refreshableRouteResolver) {
        this.refreshableRouteResolver = refreshableRouteResolver;
    }

    @WriteOperation
    public void reload() {
        refreshableRouteResolver.reload();
    }
}

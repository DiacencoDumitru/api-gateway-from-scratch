package com.dumitrudiacenco.apigatewayfromscratch.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.routing")
public class GatewayRoutingProperties {

    private List<Route> routes = new ArrayList<>();

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes != null ? routes : new ArrayList<>();
    }

    public static class Route {

        private String pathPrefix;
        private String targetBaseUrl;
        private Integer retryAttempts;
        private Integer circuitBreakerFailureThreshold;
        private Long circuitBreakerOpenWaitMillis;

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public String getTargetBaseUrl() {
            return targetBaseUrl;
        }

        public void setTargetBaseUrl(String targetBaseUrl) {
            this.targetBaseUrl = targetBaseUrl;
        }

        public Integer getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(Integer retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public Integer getCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold;
        }

        public void setCircuitBreakerFailureThreshold(Integer circuitBreakerFailureThreshold) {
            this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        }

        public Long getCircuitBreakerOpenWaitMillis() {
            return circuitBreakerOpenWaitMillis;
        }

        public void setCircuitBreakerOpenWaitMillis(Long circuitBreakerOpenWaitMillis) {
            this.circuitBreakerOpenWaitMillis = circuitBreakerOpenWaitMillis;
        }
    }
}

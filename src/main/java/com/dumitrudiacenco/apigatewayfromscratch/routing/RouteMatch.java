package com.dumitrudiacenco.apigatewayfromscratch.routing;

import java.net.URI;

public record RouteMatch(URI upstreamUri, int retryAttempts) {}

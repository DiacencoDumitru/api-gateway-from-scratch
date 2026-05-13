package com.dumitrudiacenco.apigatewayfromscratch.resilience;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RouteCircuitBreakers {

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public void resetAll() {
        breakers.clear();
    }

    public boolean allowRequest(String routeKey, int failureThreshold, long openWaitMillis) {
        if (failureThreshold <= 0 || openWaitMillis <= 0) {
            return true;
        }
        return breakers.computeIfAbsent(routeKey, key -> new Breaker()).allow(failureThreshold, openWaitMillis);
    }

    public void recordSuccess(String routeKey, int failureThreshold) {
        if (failureThreshold <= 0) {
            return;
        }
        Breaker breaker = breakers.get(routeKey);
        if (breaker != null) {
            breaker.recordSuccess();
        }
    }

    public void recordFailure(String routeKey, int failureThreshold, long openWaitMillis) {
        if (failureThreshold <= 0 || openWaitMillis <= 0) {
            return;
        }
        breakers.computeIfAbsent(routeKey, key -> new Breaker()).recordFailure(failureThreshold, openWaitMillis);
    }

    private static final class Breaker {

        private enum Phase {
            CLOSED,
            OPEN,
            HALF_OPEN
        }

        private Phase phase = Phase.CLOSED;
        private int failures;
        private long openUntilNanos;

        synchronized boolean allow(int failureThreshold, long openWaitMillis) {
            long now = System.nanoTime();
            if (phase == Phase.OPEN) {
                if (now >= openUntilNanos) {
                    phase = Phase.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return true;
        }

        synchronized void recordSuccess() {
            failures = 0;
            phase = Phase.CLOSED;
        }

        synchronized void recordFailure(int failureThreshold, long openWaitMillis) {
            long waitNanos = Math.max(1L, openWaitMillis) * 1_000_000L;
            if (phase == Phase.HALF_OPEN) {
                phase = Phase.OPEN;
                openUntilNanos = System.nanoTime() + waitNanos;
                failures = 0;
                return;
            }
            failures++;
            if (failures >= failureThreshold) {
                phase = Phase.OPEN;
                openUntilNanos = System.nanoTime() + waitNanos;
                failures = 0;
            }
        }
    }
}

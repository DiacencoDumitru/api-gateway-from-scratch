package com.dumitrudiacenco.apigatewayfromscratch.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RouteTokenBuckets {
    private final ConcurrentHashMap<String, BucketState> states = new ConcurrentHashMap<>();

    public record TryResult(boolean allowed, long retryAfterSeconds) {}

    private static final class BucketState {
        double tokens;
        long lastRefillNanos;

        BucketState(double capacity, long nanos) {
            this.tokens = capacity;
            this.lastRefillNanos = nanos;
        }
    }

    public TryResult tryConsume(String compositeKey, int burst, long refillMillis) {
        if (burst <= 0 || refillMillis <= 0) {
            return new TryResult(true, 0L);
        }
        long now = System.nanoTime();
        double capacity = burst;
        double refillPerNano = capacity / (refillMillis * 1_000_000.0);
        BucketState state = states.computeIfAbsent(compositeKey, k -> new BucketState(capacity, now));
        synchronized (state) {
            long nowNanos = System.nanoTime();
            double elapsedNanos = Math.max(0.0, nowNanos - state.lastRefillNanos);
            state.tokens = Math.min(capacity, state.tokens + elapsedNanos * refillPerNano);
            state.lastRefillNanos = nowNanos;
            if (state.tokens >= 1.0d - 1e-9) {
                state.tokens -= 1.0d;
                return new TryResult(true, 0L);
            }
            double deficit = 1.0d - state.tokens;
            long retryNanos = (long) Math.ceil(deficit / refillPerNano);
            long retryAfterSeconds = Math.max(1L, (retryNanos + 999_999_999L) / 1_000_000_000L);
            return new TryResult(false, retryAfterSeconds);
        }
    }

    public void resetAll() {
        states.clear();
    }
}

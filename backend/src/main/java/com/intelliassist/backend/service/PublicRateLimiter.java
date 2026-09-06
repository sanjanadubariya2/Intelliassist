package com.intelliassist.backend.service;

import com.intelliassist.backend.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A per-key cap on public chat requests.
 *
 * <p>Widget endpoints are reachable by anyone who can read a customer's page
 * source, and every message costs a model call. Without a cap, one leaked key
 * could spend a customer's entire quota in minutes.
 *
 * <p><strong>In memory, and therefore per instance.</strong> With two replicas
 * the effective limit is double. That is a real limitation and an accepted one:
 * the purpose here is to stop runaway abuse, not to bill accurately, and a
 * factor-of-two error does not change that. Moving it to Redis — which is
 * already in the stack for the AI service — is the fix when this runs on more
 * than one instance, and would be a change to this class alone.
 *
 * <p>A fixed window rather than a sliding one, for the same reason: a burst at
 * a window boundary can briefly reach twice the limit, which does not matter
 * for abuse prevention and keeps the implementation to a counter and a
 * timestamp.
 */
@Component
public class PublicRateLimiter {

    /** Generous for a real conversation, ruinous for a scraper. */
    private static final int MAX_REQUESTS_PER_WINDOW = 30;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Bounds memory: a hostile caller cannot grow this without first obtaining
     * that many valid API keys, but the ceiling makes the failure mode explicit.
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    /** Raised when a caller exceeds the cap. */
    public static class RateLimitExceededException extends ApiException {
        RateLimitExceededException() {
            super(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Too many messages. Please wait a moment and try again.");
        }
    }

    /** Records one request, or throws if the caller is over the cap. */
    public void check(UUID apiKeyId) {
        if (windows.size() > MAX_TRACKED_KEYS) {
            // Every window is at most a minute old, so clearing costs a brief
            // reset rather than losing durable state.
            windows.clear();
        }

        Window window =
                windows.compute(
                        apiKeyId,
                        (key, existing) ->
                                existing == null || existing.isExpired()
                                        ? new Window()
                                        : existing);

        if (window.count.incrementAndGet() > MAX_REQUESTS_PER_WINDOW) {
            throw new RateLimitExceededException();
        }
    }

    /** One fixed window of counting. */
    private static final class Window {
        private final Instant startedAt = Instant.now();
        private final AtomicInteger count = new AtomicInteger();

        boolean isExpired() {
            return startedAt.plus(WINDOW).isBefore(Instant.now());
        }
    }
}

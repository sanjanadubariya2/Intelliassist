package com.intelliassist.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins permitted to call the dashboard API.
 *
 * <p>An explicit list, never {@code *}. The dashboard sends credentials, and a
 * wildcard origin combined with credentials is both rejected by browsers and
 * unsafe in principle.
 *
 * <p>Public widget endpoints get their own CORS treatment when they arrive in a
 * later phase: they are called from arbitrary customer sites, so they are
 * constrained by registered allowed domains rather than by this list.
 */
@ConfigurationProperties(prefix = "intelliassist.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins != null ? List.copyOf(allowedOrigins) : List.of();
    }
}

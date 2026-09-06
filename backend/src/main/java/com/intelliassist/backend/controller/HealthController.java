package com.intelliassist.backend.controller;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A lightweight liveness endpoint under the API namespace.
 *
 * <p>Actuator already provides {@code /actuator/health}, which checks the
 * database and is the right probe for readiness. This one deliberately checks
 * nothing: it answers "is the process up", which is what a liveness probe
 * should ask. Conflating the two means a brief database outage restarts a
 * perfectly healthy process and turns a recoverable blip into an outage.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String applicationName;

    public HealthController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", applicationName, Instant.now());
    }

    /** Deliberately says nothing about versions, dependencies or configuration. */
    public record HealthResponse(String status, String service, Instant timestamp) {}
}

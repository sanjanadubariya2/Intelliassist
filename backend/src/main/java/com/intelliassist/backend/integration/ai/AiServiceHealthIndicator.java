package com.intelliassist.backend.integration.ai;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the AI service is reachable.
 *
 * <p><strong>Deliberately excluded from the readiness group.</strong> If a
 * failing AI service made this application unready, an orchestrator would pull
 * it out of rotation — even though registration, login, bot management and
 * every document listing still work perfectly. Losing the whole dashboard
 * because generation is down is a worse outcome than losing generation.
 *
 * <p>So this shows up in {@code /actuator/health} as information for an
 * operator, and nothing more. See {@code application.yml}, where the readiness
 * group names only the checks that genuinely mean "cannot serve traffic".
 *
 * <p>The base URL is never reported. It names an internal host, and health
 * output is the sort of thing that ends up in dashboards and screenshots.
 */
@Component("aiService")
public class AiServiceHealthIndicator implements HealthIndicator {

    private final AiServiceClient aiServiceClient;

    public AiServiceHealthIndicator(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public Health health() {
        boolean reachable = aiServiceClient.isHealthy();

        return reachable
                ? Health.up().withDetail("reachable", true).build()
                : Health.down()
                        .withDetail("reachable", false)
                        .withDetail(
                                "impact", "Chat and document ingestion are unavailable")
                        .build();
    }
}

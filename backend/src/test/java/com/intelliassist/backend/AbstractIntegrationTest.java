package com.intelliassist.backend;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * <p>The container is {@code static} and never explicitly stopped, so a single
 * Postgres is started once and shared by every integration test in the run.
 * Ryuk removes it afterwards. Starting a container per class would add seconds
 * to every test and is the usual reason Testcontainers suites get abandoned.
 *
 * <p>A real Postgres rather than H2 is the point: Flyway migrations use
 * Postgres syntax, JSONB columns do not exist in H2 at all, and a schema that
 * only works on H2 proves nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("intelliassist_backend_test")
                    .withUsername("test")
                    .withPassword("test")
                    // Testcontainers' default 60s startup budget is enough on
                    // an idle machine and not enough on a loaded one - a
                    // developer laptop already running the AI stack, or a
                    // cold CI runner pulling the image. A generous ceiling
                    // costs nothing when startup is fast and removes a class
                    // of flake that otherwise looks like a real failure.
                    .withStartupTimeout(Duration.ofMinutes(3))
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    /**
     * Points the datasource at the container.
     *
     * <p>Supplied here rather than in {@code application-test.yml} so tests can
     * never accidentally run against a developer's real database.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

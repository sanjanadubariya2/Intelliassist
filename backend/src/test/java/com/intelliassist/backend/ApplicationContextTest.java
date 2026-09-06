package com.intelliassist.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelliassist.backend.config.AiServiceProperties;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Phase 1 gate: the application starts against a real Postgres, Flyway
 * applies the schema, and configuration binds.
 */
class ApplicationContextTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private AiServiceProperties aiServiceProperties;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayAppliedTheBaselineMigration() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {

            assertThat(rs.next()).as("at least one migration should have run").isTrue();
            assertThat(rs.getString("version")).isEqualTo("1");
            assertThat(rs.getBoolean("success")).isTrue();
        }
    }

    @Test
    void updatedAtTriggerFunctionExists() throws Exception {
        // Every domain table added in later phases attaches this trigger, so
        // its absence would break them all at once.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT 1 FROM pg_proc WHERE proname = 'set_updated_at'")) {

            assertThat(rs.next()).as("set_updated_at() should exist").isTrue();
        }
    }

    @Test
    void aiServicePropertiesBind() {
        assertThat(aiServiceProperties.baseUrl()).isEqualTo("http://localhost:9999");
        assertThat(aiServiceProperties.connectTimeout().toSeconds()).isEqualTo(2);
        assertThat(aiServiceProperties.queryTimeout().toSeconds()).isEqualTo(70);
    }

    @Test
    void aiServiceApiKeyIsNeverPrinted() {
        // Spring prints configuration properties in startup failure reports and
        // through actuator; the default record toString would print the secret.
        assertThat(aiServiceProperties.toString())
                .doesNotContain(aiServiceProperties.apiKey())
                .contains("apiKey=***");
    }
}

package com.intelliassist.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intelliassist.backend.config.CorrelationIdFilter;
import com.intelliassist.backend.config.CorsProperties;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.security.JwtService;
import com.intelliassist.backend.security.SecurityConfig;
import com.intelliassist.backend.security.PublicBotAuthenticationFilter;
import com.intelliassist.backend.service.PublicAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer only: no database, no container, so it runs in milliseconds.
 *
 * <p>A {@code @WebMvcTest} slice includes servlet filters and the security
 * filter chain, so {@link com.intelliassist.backend.security.JwtAuthenticationFilter}
 * is instantiated here — but its collaborators are a {@code @Service} and a
 * repository, which the slice deliberately excludes. Mocking them keeps the
 * test fast; the health endpoint is unauthenticated and sends no token, so the
 * filter does nothing with them.
 *
 * <p>{@link SecurityConfig} must be imported explicitly. A slice does not pick
 * up arbitrary {@code @Configuration} classes, so without it Spring Boot's
 * default "authenticate everything" chain applies and the unauthenticated
 * health endpoint returns 401 — which would be a test artefact, not a real
 * behaviour.
 */
@WebMvcTest(HealthController.class)
@Import({
    CorrelationIdFilter.class,
    SecurityConfig.class,
    PublicBotAuthenticationFilter.class
})
@EnableConfigurationProperties(CorsProperties.class)
@ActiveProfiles("test")
class HealthControllerTest {

    /**
     * A slice context has no repositories, so the widget filter's collaborator
     * is stubbed. The filter itself is real and simply passes health requests
     * through - it only acts on /api/v1/public paths.
     */
    @MockitoBean private PublicAccessService publicAccessService;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private OrganizationMemberRepository memberRepository;

    @Test
    void healthReportsOk() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("intelliassist-backend"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void healthIsReachableWithoutAToken() throws Exception {
        // An orchestrator has no credentials. A guarded probe would fail closed
        // and take the deployment down with it.
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(header().exists("X-Request-ID"));
    }

    @Test
    void inboundCorrelationIdIsPreserved() throws Exception {
        // A trace started in the frontend or a gateway must keep its identity,
        // otherwise the hop cannot be joined in the logs.
        mockMvc.perform(get("/api/v1/health").header("X-Request-ID", "trace-from-gateway"))
                .andExpect(header().string("X-Request-ID", "trace-from-gateway"));
    }

    @Test
    void hostileCorrelationIdIsSanitised() throws Exception {
        // Newlines would let a caller forge log lines, and the value is
        // reflected back into a response header.
        mockMvc.perform(get("/api/v1/health").header("X-Request-ID", "abc\r\ninjected"))
                .andExpect(header().string("X-Request-ID", "abcinjected"));
    }

    @Test
    void overlongCorrelationIdIsTruncated() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-ID", "x".repeat(500)))
                .andExpect(header().string("X-Request-ID", "x".repeat(64)));
    }
}

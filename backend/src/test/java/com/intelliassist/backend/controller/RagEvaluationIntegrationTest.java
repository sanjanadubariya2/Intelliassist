package com.intelliassist.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.RagEvaluationRunRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Storing and serving RAG evaluation results.
 *
 * <p>The interesting cases are the ones that keep the numbers honest: a
 * results file this version cannot read is refused rather than half-parsed,
 * totals that do not add up are rejected, and a null metric survives the round
 * trip as null instead of becoming a zero that reads as a failing score.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RagEvaluationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RagEvaluationRunRepository runRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String tokenA;
    private String tokenB;
    private UUID botA;
    private UUID botB;

    @BeforeEach
    void setUp() {
        runRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));
        tokenA = tokenFor(orgA, "a@example.com");
        tokenB = tokenFor(orgB, "b@example.com");

        botA = botRepository.save(new Bot(orgA, "Bot A", "d", "en")).getId();
        botB = botRepository.save(new Bot(orgB, "Bot B", "d", "en")).getId();
    }

    private String tokenFor(Organization organization, String email) {
        User user =
                userRepository.save(new User(email, passwordEncoder.encode("x"), "Test User"));
        memberRepository.save(new OrganizationMember(organization, user, MemberRole.OWNER));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    /** A results file as the Python runner writes it. */
    private static String results(String metrics, int total, int successful, int failed) {
        return """
               {
                 "schema_version": 1,
                 "dataset": "evaluation/acme.jsonl",
                 "model": "gemini-3.1-flash-lite",
                 "started_at": "2026-09-04T10:00:00Z",
                 "finished_at": "2026-09-04T10:02:00Z",
                 "totals": {"total": %d, "successful": %d, "failed": %d,
                            "unmet_expectations": 1},
                 "metrics": %s,
                 "latency": {"mean_ms": 2221.1, "p50_ms": 1914.1,
                             "p95_ms": 3176.1, "max_ms": 3176.1},
                 "cases": [
                   {"id": "q001", "question": "How much leave?", "ok": true,
                    "hit_at_k": 1.0, "cited_sources": ["HR Policy.pdf"]},
                   {"id": "q002", "question": "Weekend delivery?", "ok": true,
                    "hit_at_k": 0.0, "cited_sources": []}
                 ]
               }
               """
                .formatted(total, successful, failed, metrics);
    }

    private static final String FULL_METRICS =
            """
            {"hit_at_k": 0.5, "mrr": 0.75, "source_recall": 0.5,
             "source_precision": 1.0, "answer_token_f1": 0.852,
             "groundedness": 1.0, "answer_rate": 0.667,
             "abstention_accuracy": 1.0}
            """;

    // ------------------------------------------------------------- storing --

    @Test
    void aRunIsStoredAndReturned() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(FULL_METRICS, 2, 2, 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalQuestions").value(2))
                .andExpect(jsonPath("$.metrics.hit_at_k").value(0.5))
                .andExpect(jsonPath("$.latency.p95_ms").value(3176.1))
                .andExpect(jsonPath("$.cases.length()").value(2));
    }

    @Test
    void theLatestRunIsWhatIsReturned() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(FULL_METRICS, 2, 2, 0)))
                .andExpect(status().isCreated());

        String improved = FULL_METRICS.replace("\"hit_at_k\": 0.5", "\"hit_at_k\": 1.0");
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        results(improved, 2, 2, 0)
                                                .replace("10:02:00Z", "11:02:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.hit_at_k").value(1.0));

        // Both are kept: the direction a metric moved is the useful part.
        org.assertj.core.api.Assertions.assertThat(runRepository.count()).isEqualTo(2);
    }

    @Test
    void aNullMetricSurvivesAsNull() throws Exception {
        // A dataset that stated no expected sources measures no retrieval. That
        // must not come back as 0.0, which reads as a failing score.
        String sparse = "{\"hit_at_k\": null, \"mrr\": null, \"groundedness\": 1.0}";

        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(sparse, 1, 1, 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metrics.hit_at_k").doesNotExist())
                .andExpect(jsonPath("$.metrics.groundedness").value(1.0));
    }

    // ------------------------------------------------------------ refusals --

    @Test
    void aFutureSchemaVersionIsRefused() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        results(FULL_METRICS, 2, 2, 0)
                                                .replace("\"schema_version\": 1", "\"schema_version\": 99")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void totalsThatDoNotAddUpAreRefused() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(FULL_METRICS, 5, 2, 0)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- reading --

    @Test
    void aBotNeverEvaluatedReturnsNoContent() throws Exception {
        // 204, not an empty object: "never run" and "ran and scored nothing"
        // must not look the same to a dashboard.
        mockMvc.perform(
                        get("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------ security --

    @Test
    void anotherOrganizationCannotReadThisBotsEvaluation() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(FULL_METRICS, 2, 2, 0)))
                .andExpect(status().isCreated());

        // 404 rather than 403: a 403 would confirm the bot exists.
        mockMvc.perform(
                        get("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationCannotWriteToThisBot() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/{botId}/analytics/rag-evaluation", botA)
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(results(FULL_METRICS, 2, 2, 0)))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(runRepository.count()).isZero();
    }

    @Test
    void evaluationRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/bots/{botId}/analytics/rag-evaluation", botB))
                .andExpect(status().isUnauthorized());
    }
}

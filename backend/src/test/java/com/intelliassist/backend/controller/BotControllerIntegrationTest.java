package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.JwtService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bot CRUD and, most importantly, tenant isolation against a real database.
 *
 * <p>Two organizations exist in every test. That is the point: a rule that
 * looks correct with one tenant present proves nothing.
 */
@AutoConfigureMockMvc
class BotControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String tokenA;
    private String tokenB;
    private String memberTokenA;
    private UUID orgAId;

    @BeforeEach
    void setUp() {
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));
        orgAId = orgA.getId();

        tokenA = tokenFor(orgA, "owner-a@example.com", MemberRole.OWNER);
        tokenB = tokenFor(orgB, "owner-b@example.com", MemberRole.OWNER);
        memberTokenA = tokenFor(orgA, "member-a@example.com", MemberRole.MEMBER);
    }

    /** Creates a user with a membership and returns a token for it. */
    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("Test", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    private Map<String, Object> botPayload(String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", "A bot");
        payload.put("defaultLanguage", "en");
        return payload;
    }

    private UUID createBot(String token, String name) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/bots")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(botPayload(name))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    // ---------------------------------------------------------------- CRUD --

    @Test
    void createReturns201WithTheBot() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(botPayload("Support"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Support"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.organizationId").value(orgAId.toString()));
    }

    @Test
    void getReturnsTheBot() throws Exception {
        UUID botId = createBot(tokenA, "Support");

        mockMvc.perform(get("/api/v1/bots/" + botId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Support"));
    }

    @Test
    void listReturnsOnlyTheCallersBots() throws Exception {
        createBot(tokenA, "A One");
        createBot(tokenA, "A Two");
        createBot(tokenB, "B One");

        mockMvc.perform(get("/api/v1/bots").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void updateReplacesTheBot() throws Exception {
        UUID botId = createBot(tokenA, "Support");

        Map<String, Object> update = new HashMap<>();
        update.put("name", "Renamed");
        update.put("description", "Updated");
        update.put("status", "INACTIVE");
        update.put("defaultLanguage", "en-GB");

        mockMvc.perform(
                        put("/api/v1/bots/" + botId)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.defaultLanguage").value("en-GB"));
    }

    @Test
    void deleteRemovesTheBot() throws Exception {
        UUID botId = createBot(tokenA, "Support");

        mockMvc.perform(delete("/api/v1/bots/" + botId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        assertThat(botRepository.count()).isZero();
    }

    @Test
    void duplicateNameInTheSameOrganizationIs409() throws Exception {
        createBot(tokenA, "Support");

        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(botPayload("Support"))))
                .andExpect(status().isConflict());
    }

    @Test
    void twoOrganizationsMayBothHaveABotWithTheSameName() throws Exception {
        createBot(tokenA, "Support");

        // Forbidding this would leak that another tenant already used the name.
        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(botPayload("Support"))))
                .andExpect(status().isCreated());
    }

    // ----------------------------------------------------- TENANT ISOLATION --

    @Test
    void organizationBCannotReadOrganizationAsBot() throws Exception {
        UUID botOfA = createBot(tokenA, "A Secret Bot");

        mockMvc.perform(get("/api/v1/bots/" + botOfA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationBCannotUpdateOrganizationAsBot() throws Exception {
        UUID botOfA = createBot(tokenA, "A Secret Bot");

        Map<String, Object> update = new HashMap<>();
        update.put("name", "Hijacked");
        update.put("status", "ACTIVE");
        update.put("defaultLanguage", "en");

        mockMvc.perform(
                        put("/api/v1/bots/" + botOfA)
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());

        assertThat(botRepository.findById(botOfA).orElseThrow().getName())
                .isEqualTo("A Secret Bot");
    }

    @Test
    void organizationBCannotDeleteOrganizationAsBot() throws Exception {
        UUID botOfA = createBot(tokenA, "A Secret Bot");

        mockMvc.perform(
                        delete("/api/v1/bots/" + botOfA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        assertThat(botRepository.existsById(botOfA)).isTrue();
    }

    @Test
    void crossTenantAccessIs404Not403() throws Exception {
        UUID botOfA = createBot(tokenA, "A Secret Bot");

        // A 403 would confirm the id exists, letting B enumerate A's bots.
        mockMvc.perform(get("/api/v1/bots/" + botOfA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void listNeverLeaksAnotherOrganizationsBots() throws Exception {
        createBot(tokenB, "B One");
        createBot(tokenB, "B Two");

        mockMvc.perform(get("/api/v1/bots").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // -------------------------------------------------------------- roles --

    @Test
    void memberCannotCreateABot() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + memberTokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(botPayload("Nope"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void memberCanListBots() throws Exception {
        createBot(tokenA, "Support");

        mockMvc.perform(get("/api/v1/bots").header("Authorization", "Bearer " + memberTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void memberCannotDeleteABot() throws Exception {
        UUID botId = createBot(tokenA, "Support");

        mockMvc.perform(
                        delete("/api/v1/bots/" + botId)
                                .header("Authorization", "Bearer " + memberTokenA))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------- auth + input --

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        mockMvc.perform(get("/api/v1/bots")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankNameIsRejectedWithFieldDetail() throws Exception {
        Map<String, Object> payload = botPayload("   ");

        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void malformedLanguageTagIsRejected() throws Exception {
        Map<String, Object> payload = botPayload("Support");
        payload.put("defaultLanguage", "English");

        mockMvc.perform(
                        post("/api/v1/bots")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("defaultLanguage"));
    }

    @Test
    void malformedBotIdIs400NotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/bots/not-a-uuid").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedPageRequestIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots?size=5000").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownBotIs404() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + UUID.randomUUID())
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}

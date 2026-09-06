package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The full authentication flow against a real database. */
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeEach
    void clean() {
        // Order matters: memberships reference both other tables.
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private String registerAndGetToken(String email, String organizationName) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "name", "Test User",
                                                                "email", email,
                                                                "password", PASSWORD,
                                                                "organizationName",
                                                                        organizationName))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    // ------------------------------------------------------------ register --

    @Test
    void registrationCreatesUserOrganizationAndOwnerMembership() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Ada Lovelace",
                                                        "email", "ada@example.com",
                                                        "password", PASSWORD,
                                                        "organizationName", "Acme Corp"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.organization.slug").value("acme-corp"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(organizationRepository.count()).isEqualTo(1);
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void passwordIsHashedNotStored() throws Exception {
        registerAndGetToken("hash@example.com", "Hash Co");

        String stored = userRepository.findByEmail("hash@example.com").orElseThrow().getPasswordHash();

        assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
    }

    @Test
    void responseNeverContainsThePasswordOrItsHash() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "name", "Leak Test",
                                                                "email", "leak@example.com",
                                                                "password", PASSWORD,
                                                                "organizationName", "Leak Co"))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash").doesNotContain("$2");
    }

    @Test
    void emailIsNormalisedToLowercase() throws Exception {
        registerAndGetToken("MiXeD@Example.COM", "Case Co");

        assertThat(userRepository.findByEmail("mixed@example.com")).isPresent();
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        registerAndGetToken("dupe@example.com", "First Co");

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Second",
                                                        "email", "dupe@example.com",
                                                        "password", PASSWORD,
                                                        "organizationName", "Second Co"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void duplicateEmailIsCaseInsensitive() throws Exception {
        registerAndGetToken("case@example.com", "First Co");

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Second",
                                                        "email", "CASE@EXAMPLE.COM",
                                                        "password", PASSWORD,
                                                        "organizationName", "Second Co"))))
                .andExpect(status().isConflict());
    }

    @Test
    void collidingOrganizationNamesGetDistinctSlugs() throws Exception {
        registerAndGetToken("one@example.com", "Acme");
        registerAndGetToken("two@example.com", "Acme");

        assertThat(organizationRepository.findBySlug("acme")).isPresent();
        assertThat(organizationRepository.findBySlug("acme-2")).isPresent();
    }

    @Test
    void shortPasswordIsRejectedWithFieldDetail() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Weak",
                                                        "email", "weak@example.com",
                                                        "password", "short",
                                                        "organizationName", "Weak Co"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("password"));
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Bad",
                                                        "email", "not-an-email",
                                                        "password", PASSWORD,
                                                        "organizationName", "Bad Co"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("email"));
    }

    // --------------------------------------------------------------- login --

    @Test
    void loginWithCorrectCredentialsReturnsAToken() throws Exception {
        registerAndGetToken("login@example.com", "Login Co");

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "email", "login@example.com",
                                                        "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheIdenticalResponse() throws Exception {
        registerAndGetToken("real@example.com", "Real Co");

        String wrongPassword =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "email", "real@example.com",
                                                                "password", "wrong-password"))))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String unknownEmail =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "email", "nobody@example.com",
                                                                "password", "wrong-password"))))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Distinguishing the two would turn login into an oracle for which
        // email addresses have accounts.
        JsonNode a = objectMapper.readTree(wrongPassword);
        JsonNode b = objectMapper.readTree(unknownEmail);
        assertThat(a.get("message").asText()).isEqualTo(b.get("message").asText());
        assertThat(a.get("error").asText()).isEqualTo(b.get("error").asText());
    }

    // ------------------------------------------------------------------ me --

    @Test
    void meReturnsTheCallerIdentityAndRole() throws Exception {
        String token = registerAndGetToken("me@example.com", "Me Co");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("me@example.com"))
                .andExpect(jsonPath("$.organization.slug").value("me-co"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.organizations.length()").value(1));
    }

    @Test
    void meWithoutATokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void meWithAGarbageTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutTheBearerPrefixIs401() throws Exception {
        String token = registerAndGetToken("raw@example.com", "Raw Co");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------- membership revocation --

    @Test
    void tokenStopsWorkingAsSoonAsTheMembershipIsRemoved() throws Exception {
        String token = registerAndGetToken("revoked@example.com", "Revoked Co");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The token is still cryptographically valid and unexpired. Access must
        // stop anyway, because the membership behind it is gone - which is why
        // the role is read from the database rather than carried in the token.
        memberRepository.deleteAll();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------- tenant isolation ---

    @Test
    void aTokenIsScopedToItsOwnOrganization() throws Exception {
        String tokenA = registerAndGetToken("owner-a@example.com", "Org A");
        String tokenB = registerAndGetToken("owner-b@example.com", "Org B");

        UUID orgA =
                UUID.fromString(
                        objectMapper
                                .readTree(
                                        mockMvc.perform(
                                                        get("/api/v1/auth/me")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + tokenA))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .at("/organization/id")
                                .asText());

        UUID orgB =
                UUID.fromString(
                        objectMapper
                                .readTree(
                                        mockMvc.perform(
                                                        get("/api/v1/auth/me")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + tokenB))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .at("/organization/id")
                                .asText());

        // The foundation every later authorization check builds on: two tenants
        // resolve to genuinely different organizations, and neither token can
        // name the other.
        assertThat(orgA).isNotEqualTo(orgB);
        assertThat(memberRepository.existsByUserIdAndOrganizationId(
                        userRepository.findByEmail("owner-a@example.com").orElseThrow().getId(),
                        orgB))
                .isFalse();
    }

    @Test
    void ownerRoleIsAssignedToTheRegisteringUser() throws Exception {
        registerAndGetToken("role@example.com", "Role Co");

        var member = memberRepository.findAll().get(0);
        assertThat(member.getRole()).isEqualTo(MemberRole.OWNER);
    }
}

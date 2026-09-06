package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.AllowedDomain;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.AllowedDomainRepository;
import com.intelliassist.backend.repository.ApiKeyRepository;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationNoteRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.ApiKeyHasher;
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
 * Widget customization, conversation resolution and internal notes.
 *
 * <p>The two that matter most: appearance must be readable by the widget's own
 * key without leaking anything else about the bot, and notes are staff
 * commentary about a visitor, so they must never reach the public API.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomizationIntegrationTest extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://acme.example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private BotRepository botRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationNoteRepository noteRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private AllowedDomainRepository allowedDomainRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String tokenA;
    private String tokenB;
    private UUID botA;
    private UUID conversationA;
    private String rawKeyA;

    @BeforeEach
    void setUp() {
        noteRepository.deleteAll();
        conversationRepository.deleteAll();
        allowedDomainRepository.deleteAll();
        apiKeyRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));
        tokenA = tokenFor(orgA, "a@example.com", "Ada Lovelace");
        tokenB = tokenFor(orgB, "b@example.com", "Grace Hopper");

        Bot a = botRepository.save(new Bot(orgA, "Bot A", "d", "en"));
        botA = a.getId();
        conversationA = conversationRepository.save(new Conversation(a, "visitor-1")).getId();

        var generated = ApiKeyHasher.generate();
        rawKeyA = generated.raw();
        apiKeyRepository.save(
                new com.intelliassist.backend.entity.ApiKey(
                        a, "Site", generated.hash(), generated.prefix()));
        allowedDomainRepository.save(new AllowedDomain(a, ALLOWED_ORIGIN));
    }

    private String tokenFor(Organization organization, String email, String name) {
        User user = userRepository.save(new User(name, email, passwordEncoder.encode("x")));
        memberRepository.save(new OrganizationMember(organization, user, MemberRole.OWNER));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    // ------------------------------------------------------------ appearance --

    @Test
    void aNewBotHasSensibleAppearanceDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/bots/{botId}", botA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#7C5AED"))
                .andExpect(jsonPath("$.widgetPosition").value("BOTTOM_RIGHT"))
                .andExpect(jsonPath("$.launcherIcon").value("CHAT"))
                .andExpect(jsonPath("$.welcomeMessage").isNotEmpty())
                .andExpect(jsonPath("$.fallbackMessage").isNotEmpty());
    }

    @Test
    void appearanceCanBeChanged() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/bots/{botId}/appearance", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"primaryColor": "#FF5722",
                                         "widgetPosition": "BOTTOM_LEFT",
                                         "launcherIcon": "SPARKLE",
                                         "welcomeMessage": "Hello from Acme"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#FF5722"))
                .andExpect(jsonPath("$.widgetPosition").value("BOTTOM_LEFT"))
                .andExpect(jsonPath("$.launcherIcon").value("SPARKLE"))
                .andExpect(jsonPath("$.welcomeMessage").value("Hello from Acme"));
    }

    @Test
    void anOmittedFieldIsLeftAlone() throws Exception {
        // The Customization screen saves one section at a time. A partial save
        // that blanked the other sections would be a data-loss bug.
        mockMvc.perform(
                        patch("/api/v1/bots/{botId}/appearance", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"primaryColor\": \"#00AA88\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#00AA88"))
                .andExpect(jsonPath("$.launcherIcon").value("CHAT"))
                .andExpect(jsonPath("$.welcomeMessage").isNotEmpty());
    }

    @Test
    void aColourThatIsNotHexIsRejected() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/bots/{botId}/appearance", botA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"primaryColor\": \"red; background: url(evil)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOrganizationCannotRestyleThisBot() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/bots/{botId}/appearance", botA)
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"primaryColor\": \"#000000\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------- widget config --

    @Test
    void theWidgetCanReadItsOwnAppearance() throws Exception {
        mockMvc.perform(
                        get("/api/v1/public/config")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bot A"))
                .andExpect(jsonPath("$.primaryColor").value("#7C5AED"))
                // Nothing beyond appearance: no organization, no system prompt,
                // no timestamps. A visitor's browser has no business holding them.
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andExpect(jsonPath("$.systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void widgetConfigStillNeedsAValidKeyAndOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/public/config").header("Origin", ALLOWED_ORIGIN))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));

        mockMvc.perform(
                        get("/api/v1/public/config")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", "https://attacker.example.net"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // ------------------------------------------------------------ resolution --

    @Test
    void aConversationStartsUnresolved() throws Exception {
        mockMvc.perform(
                        get("/api/v1/conversations/{id}", conversationA)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("UNRESOLVED"));
    }

    @Test
    void aConversationCanBeMarkedResolvedWithoutClosing() throws Exception {
        // The two are independent: an operator judging the outcome must not
        // silence a visitor who is still typing.
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("RESOLVED"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aConversationCanBeEscalated() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"ESCALATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("ESCALATED"));
    }

    @Test
    void anUnknownResolutionIsRejected() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"SOLVED_ISH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOrganizationCannotResolveThisConversation() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationA)
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"RESOLVED\"}"))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- notes --

    @Test
    void aNoteRecordsItsAuthor() throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\": \"Refunded manually, see ticket 91.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Refunded manually, see ticket 91."))
                // Taken from the principal, never from the request: a caller
                // must not be able to sign someone else's name to a note.
                .andExpect(jsonPath("$.authorName").value("Ada Lovelace"));
    }

    @Test
    void notesAreListedOldestFirst() throws Exception {
        for (String body : new String[] {"first", "second"}) {
            mockMvc.perform(
                            post("/api/v1/conversations/{id}/notes", conversationA)
                                    .header("Authorization", "Bearer " + tokenA)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"body\": \"%s\"}".formatted(body)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(
                        get("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].body").value("first"));
    }

    @Test
    void anEmptyNoteIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOrganizationCannotReadStaffNotes() throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\": \"Visitor was abusive.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void notesAreNotReachableFromThePublicApi() throws Exception {
        // The widget's key is not a way into staff commentary about the visitor
        // it belongs to.
        mockMvc.perform(
                        get("/api/v1/conversations/{id}/notes", conversationA)
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN))
                // Denied, and the exact code is not the point: a widget key is
                // simply not a credential for a dashboard route.
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // --------------------------------------------------------------- members --

    @Test
    void membersListsOnlyTheCallersOwnOrganization() throws Exception {
        mockMvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("a@example.com"))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                // The projection never selects it, so it cannot leak.
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void membersRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------- storage --

    @Test
    void deletingAConversationTakesItsNotesWithIt() throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations/{id}/notes", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\": \"note\"}"))
                .andExpect(status().isCreated());

        assertThat(noteRepository.count()).isEqualTo(1);

        conversationRepository.deleteById(conversationA);

        assertThat(noteRepository.count()).isZero();
    }

    @Test
    void resolutionSurvivesAReload() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationA)
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"ESCALATED\"}"))
                .andExpect(status().isOk());

        assertThat(conversationRepository.findById(conversationA))
                .get()
                .extracting(Conversation::getResolution)
                .isEqualTo(ConversationResolution.ESCALATED);
    }
}

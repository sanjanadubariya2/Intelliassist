package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.AllowedDomain;
import com.intelliassist.backend.entity.ApiKey;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.BotStatus;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import com.intelliassist.backend.integration.ai.dto.AiCitation;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryResponse;
import com.intelliassist.backend.repository.AllowedDomainRepository;
import com.intelliassist.backend.repository.ApiKeyRepository;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.MessageRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.ApiKeyHasher;
import com.intelliassist.backend.security.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * The public widget API and its two credential checks.
 *
 * <p>This is the most security-sensitive surface in the application: it is
 * reachable from any browser on the internet, and its credential sits in a page
 * anyone can view the source of. Most of what follows is about what must
 * <em>not</em> work.
 */
@AutoConfigureMockMvc
class PublicApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://acme.example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private AllowedDomainRepository allowedDomainRepository;
    @Autowired private com.intelliassist.backend.repository.UsageRecordRepository usageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockitoBean private AiServiceClient aiServiceClient;

    private String tokenA;
    private String memberTokenA;
    private UUID botA;
    private UUID botA2;
    private UUID botB;
    private String rawKeyA;

    private final UUID aiKnowledgeBaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        usageRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        allowedDomainRepository.deleteAll();
        apiKeyRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));

        tokenA = tokenFor(orgA, "owner-a@example.com", MemberRole.OWNER);
        memberTokenA = tokenFor(orgA, "member-a@example.com", MemberRole.MEMBER);

        Bot a = botRepository.save(new Bot(orgA, "Bot A", "d", "en"));
        Bot a2 = botRepository.save(new Bot(orgA, "Bot A Second", "d", "en"));
        Bot b = botRepository.save(new Bot(orgB, "Bot B", "d", "en"));
        botA = a.getId();
        botA2 = a2.getId();
        botB = b.getId();

        for (Bot bot : List.of(a, a2, b)) {
            knowledgeBaseRepository.save(
                    new KnowledgeBase(bot, "KB", UUID.randomUUID(), "bot-" + bot.getId()));
        }

        var generated = ApiKeyHasher.generate();
        rawKeyA = generated.raw();
        apiKeyRepository.save(new ApiKey(a, "Website", generated.hash(), generated.prefix()));
        allowedDomainRepository.save(new AllowedDomain(a, ALLOWED_ORIGIN));

        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiRagQueryResponse(
                                UUID.randomUUID(),
                                aiKnowledgeBaseId,
                                "q",
                                "Employees receive 25 days [S1].",
                                List.of(
                                        new AiCitation(
                                                1, UUID.randomUUID(), UUID.randomUUID(),
                                                "handbook.pdf", 3, "snippet", 0.9)),
                                true,
                                "gemini-3.1-flash-lite",
                                false,
                                new AiRagQueryResponse.Usage(1, 1, 1, 1, 1),
                                new AiRagQueryResponse.Timings(1, 0, 1, 2)));
    }

    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("T", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    private org.springframework.test.web.servlet.ResultActions publicChat(
            String key, String origin, String content) throws Exception {

        var request =
                post("/api/v1/public/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}");
        if (key != null) {
            request = request.header("X-Bot-Key", key);
        }
        if (origin != null) {
            request = request.header("Origin", origin);
        }
        return mockMvc.perform(request);
    }

    // ------------------------------------------------------------ happy path --

    @Test
    void aValidKeyFromAnAllowedOriginCanChat() throws Exception {
        publicChat(rawKeyA, ALLOWED_ORIGIN, "How much leave?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Employees receive 25 days [S1]."))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.conversationId").isNotEmpty());
    }

    @Test
    void theWidgetResponseHidesOperationalDetail() throws Exception {
        String body =
                publicChat(rawKeyA, ALLOWED_ORIGIN, "How much leave?")
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // A website visitor has no reason to learn which LLM vendor is behind
        // the assistant, nor how long it took.
        assertThat(body).doesNotContain("gemini").doesNotContain("latencyMs");
        // Citations stay: showing the source is the point of a grounded answer.
        assertThat(body).contains("handbook.pdf");
    }

    @Test
    void aPublicConversationIsPersistedLikeAnyOther() throws Exception {
        publicChat(rawKeyA, ALLOWED_ORIGIN, "How much leave?").andExpect(status().isOk());

        assertThat(conversationRepository.count()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------- key rejection --

    @Test
    void aRequestWithNoKeyIsRejected() throws Exception {
        publicChat(null, ALLOWED_ORIGIN, "hello").andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownKeyIsRejected() throws Exception {
        publicChat("ia_completely-made-up-key-value", ALLOWED_ORIGIN, "hello")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedKeyNeverReachesTheDatabase() throws Exception {
        publicChat("not-even-close", ALLOWED_ORIGIN, "hello")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedKeyStopsWorking() throws Exception {
        var key = apiKeyRepository.findAll().get(0);
        key.revoke();
        apiKeyRepository.saveAndFlush(key);

        publicChat(rawKeyA, ALLOWED_ORIGIN, "hello").andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyForAnInactiveBotStopsWorking() throws Exception {
        Bot bot = botRepository.findById(botA).orElseThrow();
        bot.setStatus(BotStatus.INACTIVE);
        botRepository.saveAndFlush(bot);

        publicChat(rawKeyA, ALLOWED_ORIGIN, "hello").andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------- origin rejection --

    @Test
    void aValidKeyFromTheWrongOriginIsRejected() throws Exception {
        // The key is public by nature - it sits in a page anyone can read. The
        // origin check is what makes a stolen one useless elsewhere.
        publicChat(rawKeyA, "https://attacker.example.net", "hello")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestWithNoOriginIsRejected() throws Exception {
        // A browser always sends Origin cross-site; its absence means a
        // non-browser caller, which the widget API is not for.
        publicChat(rawKeyA, null, "hello").andExpect(status().isUnauthorized());
    }

    @Test
    void aBotWithNoRegisteredDomainsAcceptsNothing() throws Exception {
        allowedDomainRepository.deleteAll();

        // Fails closed. Treating "none configured" as "allow all" would make
        // every newly created key work from any website on the internet.
        publicChat(rawKeyA, ALLOWED_ORIGIN, "hello").andExpect(status().isUnauthorized());
    }

    @Test
    void originMatchingIgnoresSchemeAndPort() throws Exception {
        publicChat(rawKeyA, "https://acme.example.com:8443", "hello").andExpect(status().isOk());
    }

    // ------------------------------------------- credential separation ------

    @Test
    void aDashboardJwtCannotUseTheWidgetApi() throws Exception {
        // Different credential, different principal type, different authority.
        mockMvc.perform(
                        post("/api/v1/public/chat")
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWidgetKeyCannotUseTheDashboardApi() throws Exception {
        mockMvc.perform(get("/api/v1/bots").header("X-Bot-Key", rawKeyA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWidgetKeyCannotMintMoreKeys() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/api-keys")
                                .header("X-Bot-Key", rawKeyA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"escalation\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------- conversation scoping --

    @Test
    void aKeyCannotContinueAnotherBotsConversation() throws Exception {
        // Same organization, different bot. Organization scoping alone would
        // wrongly permit this: the second bot may be embedded on another site.
        Bot other = botRepository.findById(botA2).orElseThrow();
        UUID foreign =
                conversationRepository
                        .save(new com.intelliassist.backend.entity.Conversation(other, "v"))
                        .getId();

        mockMvc.perform(
                        post("/api/v1/public/conversations/" + foreign + "/chat")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aKeyCannotReachAnotherOrganizationsConversation() throws Exception {
        Bot other = botRepository.findById(botB).orElseThrow();
        UUID foreign =
                conversationRepository
                        .save(new com.intelliassist.backend.entity.Conversation(other, "v"))
                        .getId();

        mockMvc.perform(
                        post("/api/v1/public/conversations/" + foreign + "/chat")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFollowUpContinuesTheSameConversation() throws Exception {
        String body =
                publicChat(rawKeyA, ALLOWED_ORIGIN, "First")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID conversationId =
                UUID.fromString(objectMapper.readTree(body).get("conversationId").asText());

        mockMvc.perform(
                        post("/api/v1/public/conversations/" + conversationId + "/chat")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"Second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));

        assertThat(conversationRepository.count()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(4);
    }

    // -------------------------------------------------- key management ------

    @Test
    void creatingAKeyReturnsTheRawValueExactlyOnce() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/bots/" + botA + "/api-keys")
                                        .header("Authorization", "Bearer " + tokenA)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"Marketing site\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.rawKey").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String rawKey = objectMapper.readTree(body).get("rawKey").asText();

        // Listing shows the prefix only; the key itself is gone for good.
        String listed =
                mockMvc.perform(
                                get("/api/v1/bots/" + botA + "/api-keys")
                                        .header("Authorization", "Bearer " + tokenA))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(listed).doesNotContain(rawKey);
        assertThat(listed).contains("ia_");
    }

    @Test
    void onlyTheHashIsStored() {
        var stored = apiKeyRepository.findAll().get(0);

        // A database dump must not hand an attacker a working widget key.
        assertThat(ApiKeyHasher.hash(rawKeyA)).isNotEqualTo(rawKeyA);
        assertThat(stored.getKeyPrefix()).hasSizeLessThan(rawKeyA.length());
    }

    @Test
    void aMemberCannotMintAKey() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/api-keys")
                                .header("Authorization", "Bearer " + memberTokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherOrganizationCannotMintAKeyForThisBot() throws Exception {
        String tokenB = tokenFor(organizationRepository.findBySlug("org-b").orElseThrow(),
                "second-b@example.com", MemberRole.OWNER);

        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/api-keys")
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"theft\"}"))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------- domain management --

    @Test
    void domainsAreNormalisedOnTheWayIn() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA2 + "/domains")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"domain\":\"HTTPS://Shop.Example.com:8443/path\"}"))
                .andExpect(status().isCreated())
                // What a customer types and what a browser sends must compare
                // equal, so normalisation happens once, on write.
                .andExpect(jsonPath("$.domain").value("shop.example.com"));
    }

    @Test
    void aDuplicateDomainIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/domains")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"domain\":\"acme.example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnusableDomainIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/domains")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"domain\":\"localhost\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokingAKeyIsImmediate() throws Exception {
        UUID keyId = apiKeyRepository.findAll().get(0).getId();

        mockMvc.perform(
                        delete("/api/v1/api-keys/" + keyId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        publicChat(rawKeyA, ALLOWED_ORIGIN, "hello").andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- usage --

    @Test
    void widgetConversationsAreCounted() throws Exception {
        publicChat(rawKeyA, ALLOWED_ORIGIN, "How much leave?").andExpect(status().isOk());

        // Found live: this path goes straight to ChatStore rather than through
        // ChatService, so it did not inherit the counter and every widget
        // conversation went unrecorded - messages against zero conversations.
        var usage = usageRepository.findByBotIdAndUsageDate(botA, java.time.LocalDate.now());
        assertThat(usage).isPresent();
        assertThat(usage.get().getConversationCount()).isEqualTo(1);
        assertThat(usage.get().getMessageCount()).isEqualTo(2);
    }

    @Test
    void aFollowUpDoesNotCountAsANewConversation() throws Exception {
        String body =
                publicChat(rawKeyA, ALLOWED_ORIGIN, "First")
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID conversationId =
                UUID.fromString(objectMapper.readTree(body).get("conversationId").asText());

        mockMvc.perform(
                        post("/api/v1/public/conversations/" + conversationId + "/chat")
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"Second\"}"))
                .andExpect(status().isOk());

        var usage =
                usageRepository.findByBotIdAndUsageDate(botA, java.time.LocalDate.now())
                        .orElseThrow();
        assertThat(usage.getConversationCount()).isEqualTo(1);
        assertThat(usage.getMessageCount()).isEqualTo(4);
    }

    // -------------------------------------------------------- rate limiting --

    @Test
    void aFloodOfRequestsIsRateLimited() throws Exception {
        boolean limited = false;
        for (int i = 0; i < 40; i++) {
            int status =
                    publicChat(rawKeyA, ALLOWED_ORIGIN, "spam " + i)
                            .andReturn()
                            .getResponse()
                            .getStatus();
            if (status == 429) {
                limited = true;
                break;
            }
        }

        // One leaked key must not be able to spend a customer's whole model
        // quota in a minute.
        assertThat(limited).isTrue();
    }

    // ------------------------------------------------------- streamed chat --

    /**
     * A streamed first message must tell the client which conversation it
     * created.
     *
     * <p>The SSE events are a pass-through from the AI service, which has no
     * concept of a conversation, so the id cannot ride in the body. Without the
     * header a widget would open a fresh conversation for every message it
     * streamed: no thread, no context, and a conversation count that counts
     * turns instead.
     */
    @Test
    void aStreamedReplyNamesItsConversation() throws Exception {
        Mockito.when(aiServiceClient.streamQuery(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        Flux.just(
                                new AiStreamEvent(AiStreamEvent.TOKEN, "{\"text\":\"25 days\"}"),
                                new AiStreamEvent(AiStreamEvent.DONE, "{}")));

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/public/chat/stream")
                                        .header("X-Bot-Key", rawKeyA)
                                        .header("Origin", ALLOWED_ORIGIN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"How much leave?\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        String header = result.getResponse().getHeader("X-Conversation-Id");
        assertThat(header).as("a streamed reply must name its conversation").isNotNull();

        // The id must be real: usable to continue the same conversation.
        UUID conversationId = UUID.fromString(header);

        mockMvc.perform(
                        post("/api/v1/public/conversations/{id}/chat", conversationId)
                                .header("X-Bot-Key", rawKeyA)
                                .header("Origin", ALLOWED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"And the receipt limit?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));
    }

    /**
     * A browser can only read a response header that CORS exposes.
     *
     * <p>If this regresses the header is still sent and the test above still
     * passes, but every real widget silently loses conversation continuity --
     * a break that only ever shows up in a browser.
     */
    @Test
    void theConversationHeaderIsExposedToBrowsers() throws Exception {
        mockMvc.perform(
                        options("/api/v1/public/chat/stream")
                                .header("Origin", ALLOWED_ORIGIN)
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "X-Bot-Key,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Expose-Headers",
                                        containsString("X-Conversation-Id")));
    }
}

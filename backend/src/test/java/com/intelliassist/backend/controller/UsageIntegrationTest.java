package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.dto.AiCitation;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryResponse;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.MessageRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UsageRecordRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.JwtService;
import com.intelliassist.backend.service.UsageService;
import java.time.LocalDate;
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

/** Usage counting and reporting. */
@AutoConfigureMockMvc
class UsageIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private UsageRecordRepository usageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private UsageService usageService;

    @MockitoBean private AiServiceClient aiServiceClient;

    private String tokenA;
    private String tokenB;
    private UUID botA;
    private UUID botB;

    @BeforeEach
    void setUp() {
        usageRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));
        tokenA = tokenFor(orgA, "a@example.com", MemberRole.OWNER);
        tokenB = tokenFor(orgB, "b@example.com", MemberRole.OWNER);

        Bot a = botRepository.save(new Bot(orgA, "Bot A", "d", "en"));
        Bot b = botRepository.save(new Bot(orgB, "Bot B", "d", "en"));
        botA = a.getId();
        botB = b.getId();

        knowledgeBaseRepository.save(new KnowledgeBase(a, "KB", UUID.randomUUID(), "bot-" + botA));
        knowledgeBaseRepository.save(new KnowledgeBase(b, "KB", UUID.randomUUID(), "bot-" + botB));

        stubAnswer(true);
    }

    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("T", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    private void stubAnswer(boolean grounded) {
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiRagQueryResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "q",
                                grounded ? "Answer [S1]." : "I don't have information about that.",
                                // A grounded answer cites what it used; that is
                                // what the backend now counts as grounded.
                                grounded
                                        ? List.of(
                                                new AiCitation(
                                                        1,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        "faq.pdf",
                                                        1,
                                                        "snippet",
                                                        0.9))
                                        : List.of(),
                                grounded,
                                "m",
                                false,
                                new AiRagQueryResponse.Usage(100, 20, 50, 3, 1),
                                new AiRagQueryResponse.Timings(1, 0, 1, 2)));
    }

    private UUID startConversation(String token, UUID botId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/bots/" + botId + "/conversations")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(body)
                        .get("id")
                        .asText());
    }

    private void sendMessage(String token, UUID conversationId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations/" + conversationId + "/messages")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"How much leave?\"}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------ recording --

    @Test
    void startingAConversationIsCounted() throws Exception {
        startConversation(tokenA, botA);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        assertThat(record.getConversationCount()).isEqualTo(1);
        assertThat(record.getMessageCount()).isZero();
    }

    @Test
    void anExchangeCountsTwoMessages() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        // The question and the answer, matching what a transcript contains.
        assertThat(record.getMessageCount()).isEqualTo(2);
        assertThat(record.getTokenCount()).isEqualTo(120);
    }

    @Test
    void ungroundedAnswersAreCountedSeparately() throws Exception {
        stubAnswer(false);
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        // The number that reveals a gap in the customer's corpus.
        assertThat(record.getUngroundedCount()).isEqualTo(1);
    }

    @Test
    void groundedAnswersDoNotCountAsUngrounded() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        assertThat(record.getUngroundedCount()).isZero();
    }

    /**
     * Retrieval cleared the threshold, so the AI service says grounded, but the
     * model declined and cited nothing. That is a gap in the corpus, and
     * counting it as grounded hid every such question from insights.
     */
    @Test
    void anUncitedAnswerIsNotGroundedWhateverTheServiceSays() throws Exception {
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiRagQueryResponse(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "q",
                                "I don't have information about that in the knowledge base.",
                                List.of(),
                                true,
                                "m",
                                false,
                                new AiRagQueryResponse.Usage(100, 20, 50, 3, 0),
                                new AiRagQueryResponse.Timings(1, 0, 1, 2)));

        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        assertThat(record.getUngroundedCount()).isEqualTo(1);
    }

    @Test
    void countsAccumulateAcrossManyExchanges() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);
        sendMessage(tokenA, conversationId);
        sendMessage(tokenA, conversationId);

        var record = usageRepository.findByBotIdAndUsageDate(botA, LocalDate.now()).orElseThrow();
        assertThat(record.getMessageCount()).isEqualTo(6);
        assertThat(record.getConversationCount()).isEqualTo(1);
    }

    @Test
    void oneRowPerBotPerDay() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);
        startConversation(tokenA, botA);

        // The upsert must not create a second bucket for the same day.
        assertThat(usageRepository.findAll()).hasSize(1);
    }

    @Test
    void countsAreKeptSeparatePerBot() throws Exception {
        startConversation(tokenA, botA);
        startConversation(tokenB, botB);
        startConversation(tokenB, botB);

        assertThat(
                        usageRepository
                                .findByBotIdAndUsageDate(botA, LocalDate.now())
                                .orElseThrow()
                                .getConversationCount())
                .isEqualTo(1);
        assertThat(
                        usageRepository
                                .findByBotIdAndUsageDate(botB, LocalDate.now())
                                .orElseThrow()
                                .getConversationCount())
                .isEqualTo(2);
    }

    @Test
    void aUsageFailureNeverBreaksTheAnswer() {
        // Recording is best-effort: an unknown bot id makes the increment
        // affect nothing, and that must be silent rather than an exception.
        usageService.recordExchange(UUID.randomUUID(), true, 10);
    }

    // ------------------------------------------------------------ reporting --

    @Test
    void usageIsReportedForTheBot() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        sendMessage(tokenA, conversationId);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botId").value(botA.toString()))
                .andExpect(jsonPath("$.totalConversations").value(1))
                .andExpect(jsonPath("$.totalMessages").value(2))
                .andExpect(jsonPath("$.days.length()").value(1));
    }

    @Test
    void quietDaysAreOmittedRatherThanZeroFilled() throws Exception {
        startConversation(tokenA, botA);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage?from=2020-01-01&to=2020-12-31")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                // 366 zero rows to say nothing would be a worse payload than none.
                .andExpect(jsonPath("$.days").isEmpty())
                .andExpect(jsonPath("$.totalMessages").value(0));
    }

    @Test
    void anotherOrganizationCannotReadThisBotsUsage() throws Exception {
        startConversation(tokenA, botA);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvertedRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage?from=2025-12-31&to=2025-01-01")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAbsurdlyLongRangeIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage?from=2000-01-01&to=2030-01-01")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usageForAnUnknownBotIs404() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + UUID.randomUUID() + "/usage")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedUsageIs401() throws Exception {
        mockMvc.perform(get("/api/v1/bots/" + botA + "/usage")).andExpect(status().isUnauthorized());
    }

    // ----------------------------------------------------------- grounding --

    /**
     * Grounded answers are reported outright, not left to the client.
     *
     * <p>They cannot be derived from the payload: {@code messages} counts both
     * sides of an exchange while {@code ungroundedAnswers} counts only the
     * bot's. A client dividing one by the other reports twice the true
     * grounding rate -- which is precisely what the dashboard did before this
     * field existed.
     */
    @Test
    void groundedAnswersAreReportedDirectly() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        stubAnswer(true);
        sendMessage(tokenA, conversationId);
        sendMessage(tokenA, conversationId);

        stubAnswer(false);
        sendMessage(tokenA, conversationId);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMessages").value(6))
                .andExpect(jsonPath("$.totalAnswers").value(3))
                .andExpect(jsonPath("$.groundedAnswers").value(2))
                .andExpect(jsonPath("$.ungroundedAnswers").value(1));
    }

    /** The same split per day, so a trend chart can plot it. */
    @Test
    void eachDayCarriesItsOwnGroundedSplit() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        stubAnswer(true);
        sendMessage(tokenA, conversationId);

        stubAnswer(false);
        sendMessage(tokenA, conversationId);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/usage")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].answers").value(2))
                .andExpect(jsonPath("$.days[0].groundedAnswers").value(1))
                .andExpect(jsonPath("$.days[0].ungroundedAnswers").value(1));
    }

    /** A bot nobody has used reports zeros - not nulls, and not an error. */
    @Test
    void aBotWithNoTrafficReportsZeros() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + botB + "/usage")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").isEmpty())
                .andExpect(jsonPath("$.totalConversations").value(0))
                .andExpect(jsonPath("$.totalAnswers").value(0))
                .andExpect(jsonPath("$.groundedAnswers").value(0))
                .andExpect(jsonPath("$.ungroundedAnswers").value(0));
    }

    /** No token, no analytics. */
    @Test
    void analyticsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/bots/" + botA + "/usage"))
                .andExpect(status().isUnauthorized());
    }
}

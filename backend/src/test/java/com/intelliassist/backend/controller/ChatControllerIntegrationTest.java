package com.intelliassist.backend.controller;

import org.mockito.ArgumentCaptor;
import com.intelliassist.backend.integration.ai.dto.AiConversationTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.BotStatus;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.MessageRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.AiServiceException;
import com.intelliassist.backend.integration.ai.dto.AiCitation;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryResponse;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.MessageRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
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

/** The synchronous chat flow, with the AI service stubbed. */
@AutoConfigureMockMvc
class ChatControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockitoBean private AiServiceClient aiServiceClient;

    private String tokenA;
    private String tokenB;
    private UUID botA;
    private UUID botB;
    private UUID inactiveBot;
    private UUID botWithoutKnowledge;

    private final UUID aiKnowledgeBaseId = UUID.randomUUID();
    private final UUID aiDocumentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));

        tokenA = tokenFor(orgA, "owner-a@example.com", MemberRole.OWNER);
        tokenB = tokenFor(orgB, "owner-b@example.com", MemberRole.OWNER);

        Bot a = botRepository.save(new Bot(orgA, "Bot A", "desc", "en"));
        Bot b = botRepository.save(new Bot(orgB, "Bot B", "desc", "en"));

        Bot inactive = new Bot(orgA, "Inactive Bot", "desc", "en");
        inactive.setStatus(BotStatus.INACTIVE);
        inactive = botRepository.save(inactive);

        Bot noKnowledge = botRepository.save(new Bot(orgA, "Bare Bot", "desc", "en"));

        botA = a.getId();
        botB = b.getId();
        inactiveBot = inactive.getId();
        botWithoutKnowledge = noKnowledge.getId();

        knowledgeBaseRepository.save(
                new KnowledgeBase(a, "KB A", aiKnowledgeBaseId, "bot-" + botA));
        knowledgeBaseRepository.save(
                new KnowledgeBase(b, "KB B", UUID.randomUUID(), "bot-" + botB));
        knowledgeBaseRepository.save(
                new KnowledgeBase(inactive, "KB I", UUID.randomUUID(), "bot-" + inactiveBot));

        stubGroundedAnswer();
    }

    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("Test", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    private void stubGroundedAnswer() {
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiRagQueryResponse(
                                UUID.randomUUID(),
                                aiKnowledgeBaseId,
                                "q",
                                "Employees receive 25 days of paid leave [S1].",
                                List.of(
                                        new AiCitation(
                                                1,
                                                UUID.randomUUID(),
                                                aiDocumentId,
                                                "handbook.pdf",
                                                4,
                                                "Full-time employees receive 25 days...",
                                                0.83)),
                                true,
                                "gemini-3.1-flash-lite",
                                false,
                                new AiRagQueryResponse.Usage(400, 30, 120, 6, 1),
                                new AiRagQueryResponse.Timings(120.0, 0.0, 900.0, 1020.0)));
    }

    private void stubUngroundedAnswer() {
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiRagQueryResponse(
                                UUID.randomUUID(),
                                aiKnowledgeBaseId,
                                "q",
                                "I don't have information about that in the knowledge base.",
                                List.of(),
                                false,
                                "gemini-3.1-flash-lite",
                                false,
                                new AiRagQueryResponse.Usage(0, 0, 0, 0, 0),
                                new AiRagQueryResponse.Timings(80.0, 0.0, 0.0, 85.0)));
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

        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions send(
            String token, UUID conversationId, String content) throws Exception {
        return mockMvc.perform(
                post("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new java.util.HashMap<>(
                                java.util.Map.of("content", content)))));
    }

    // --------------------------------------------------------- conversations --

    @Test
    void startingAConversationReturns201() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/conversations")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"visitorId\":\"visitor-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visitorId").value("visitor-1"))
                .andExpect(jsonPath("$.messageCount").value(0));
    }

    @Test
    void anInactiveBotRefusesNewConversations() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + inactiveBot + "/conversations")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BOT_INACTIVE"));
    }

    // ---------------------------------------------------------------- answers --

    @Test
    void sendingAMessageReturnsBothTurnsWithCitations() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        send(tokenA, conversationId, "How much annual leave do I get?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.userMessage.content").value("How much annual leave do I get?"))
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.assistantMessage.grounded").value(true))
                .andExpect(jsonPath("$.assistantMessage.citations.length()").value(1))
                .andExpect(jsonPath("$.assistantMessage.citations[0].marker").value(1))
                .andExpect(jsonPath("$.assistantMessage.citations[0].filename").value("handbook.pdf"))
                .andExpect(jsonPath("$.assistantMessage.citations[0].pageNumber").value(4));
    }

    @Test
    void bothTurnsArePersisted() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "How much leave?").andExpect(status().isCreated());

        var messages =
                messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    void citationsSurviveARoundTripThroughJsonb() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "How much leave?").andExpect(status().isCreated());

        var assistant =
                messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).get(1);

        // Stored as JSONB and read back as typed objects.
        assertThat(assistant.getCitations()).hasSize(1);
        assertThat(assistant.getCitations().get(0).documentId()).isEqualTo(aiDocumentId);
        assertThat(assistant.getCitations().get(0).score()).isEqualTo(0.83);
    }

    @Test
    void conversationCountersAndTitleAreUpdated() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "What is the leave policy?").andExpect(status().isCreated());

        var conversation = conversationRepository.findById(conversationId).orElseThrow();

        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getLastMessageAt()).isNotNull();
        // The first question becomes the title, so a list is readable.
        assertThat(conversation.getTitle()).isEqualTo("What is the leave policy?");
    }

    @Test
    void modelAndLatencyAreRecordedForLaterAnalysis() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "How much leave?").andExpect(status().isCreated());

        var assistant =
                messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).get(1);

        assertThat(assistant.getModel()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(assistant.getTotalTokens()).isEqualTo(430);
        assertThat(assistant.getLatencyMs()).isNotNull();
    }

    @Test
    void theQueryStringIsAlwaysJustTheCurrentQuestion() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "First question").andExpect(status().isCreated());
        send(tokenA, conversationId, "Second question").andExpect(status().isCreated());

        // History travels in its own field, never concatenated into the query.
        // That string is embedded for vector search, so padding it with earlier
        // turns drags the embedding toward the previous topic and retrieves
        // worse passages -- which is the whole reason rewriting exists.
        Mockito.verify(aiServiceClient)
                .query(Mockito.eq(aiKnowledgeBaseId), Mockito.eq("First question"), Mockito.any());
        Mockito.verify(aiServiceClient)
                .query(Mockito.eq(aiKnowledgeBaseId), Mockito.eq("Second question"), Mockito.any());
    }

    @Test
    void theSecondQuestionCarriesTheFirstExchangeAsHistory() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "First question").andExpect(status().isCreated());
        send(tokenA, conversationId, "Second question").andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiConversationTurn>> history =
                ArgumentCaptor.forClass((Class<List<AiConversationTurn>>) (Class<?>) List.class);

        Mockito.verify(aiServiceClient, Mockito.times(2))
                .query(Mockito.any(), Mockito.anyString(), history.capture());

        List<List<AiConversationTurn>> sent = history.getAllValues();

        // The first question has nothing before it.
        assertThat(sent.get(0)).isEmpty();

        // The second carries the completed first exchange, oldest first, and
        // does not include itself -- it is the thing being rewritten.
        assertThat(sent.get(1)).hasSize(2);
        assertThat(sent.get(1).get(0).role()).isEqualTo("user");
        assertThat(sent.get(1).get(0).content()).isEqualTo("First question");
        assertThat(sent.get(1).get(1).role()).isEqualTo("assistant");
    }

    // ------------------------------------------------------------ ungrounded --

    @Test
    void anUngroundedAnswerIsStillA201WithNoCitations() throws Exception {
        stubUngroundedAnswer();
        UUID conversationId = startConversation(tokenA, botA);

        // Not an error: retrieval found nothing and the model was never called.
        send(tokenA, conversationId, "What is the CEO's salary?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assistantMessage.grounded").value(false))
                .andExpect(jsonPath("$.assistantMessage.citations").isEmpty())
                .andExpect(jsonPath("$.assistantMessage.content")
                        .value("I don't have information about that in the knowledge base."));
    }

    @Test
    void ungroundedAnswersArePersistedSoCorpusGapsAreVisible() throws Exception {
        stubUngroundedAnswer();
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "Unanswerable").andExpect(status().isCreated());

        var assistant =
                messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).get(1);

        assertThat(assistant.getGrounded()).isFalse();
    }

    // -------------------------------------------------------- error handling --

    @Test
    void aBotWithNoKnowledgeBaseGivesAnActionableError() throws Exception {
        UUID conversationId = startConversation(tokenA, botWithoutKnowledge);

        // "Upload a document" is something a customer can act on; a bare
        // "I don't know" would look like the bot was broken.
        send(tokenA, conversationId, "Anything?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NO_KNOWLEDGE_BASE"));
    }

    @Test
    void nothingIsPersistedWhenTheAiServiceFails() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenThrow(AiServiceException.unavailable(null));

        send(tokenA, conversationId, "How much leave?")
                .andExpect(status().isServiceUnavailable());

        // A stored question with no answer would read as though the bot
        // ignored the user.
        assertThat(messageRepository.countByConversationId(conversationId)).isZero();
    }

    @Test
    void anAiTimeoutSurfacesAsAGatewayTimeout() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenThrow(AiServiceException.timeout());

        send(tokenA, conversationId, "How much leave?").andExpect(status().isGatewayTimeout());
    }

    @Test
    void upstreamDetailNeverReachesTheClient() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        Mockito.when(aiServiceClient.query(Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenThrow(AiServiceException.upstreamFailure("llm_error", "req-9"));

        String body =
                send(tokenA, conversationId, "How much leave?")
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("gemini").doesNotContain("llm_error");
    }

    @Test
    void aClosedConversationRefusesFurtherMessages() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        mockMvc.perform(
                        post("/api/v1/conversations/" + conversationId + "/close")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        send(tokenA, conversationId, "Still there?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONVERSATION_CLOSED"));
    }

    @Test
    void blankMessagesAreRejected() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        send(tokenA, conversationId, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("content"));
    }

    @Test
    void overlongMessagesAreRejectedBeforeReachingTheAiService() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        send(tokenA, conversationId, "x".repeat(5000)).andExpect(status().isBadRequest());

        Mockito.verify(aiServiceClient, Mockito.never()).query(Mockito.any(), Mockito.anyString());
    }

    // ------------------------------------------------------------ transcripts --

    @Test
    void theTranscriptReadsInOrder() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "First").andExpect(status().isCreated());
        send(tokenA, conversationId, "Second").andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/conversations/" + conversationId + "/messages")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].content").value("First"))
                .andExpect(jsonPath("$[2].content").value("Second"));
    }

    @Test
    void conversationsAreListedForTheirBot() throws Exception {
        startConversation(tokenA, botA);
        startConversation(tokenA, botA);

        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/conversations")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    // ----------------------------------------------------- TENANT ISOLATION --

    @Test
    void organizationBCannotStartAConversationOnOrganizationAsBot() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/conversations")
                                .header("Authorization", "Bearer " + tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationBCannotReadOrganizationAsTranscript() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);
        send(tokenA, conversationId, "Confidential question").andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/conversations/" + conversationId + "/messages")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationBCannotSendIntoOrganizationAsConversation() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        send(tokenB, conversationId, "Injected message").andExpect(status().isNotFound());

        assertThat(messageRepository.countByConversationId(conversationId)).isZero();
    }

    @Test
    void conversationListsNeverCrossOrganizations() throws Exception {
        startConversation(tokenA, botA);

        mockMvc.perform(
                        get("/api/v1/bots/" + botB + "/conversations")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unauthenticatedChatIs401() throws Exception {
        UUID conversationId = startConversation(tokenA, botA);

        mockMvc.perform(
                        post("/api/v1/conversations/" + conversationId + "/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }
}

package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import com.intelliassist.backend.entity.Document;
import com.intelliassist.backend.entity.DocumentStatus;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Message;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationNoteRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.DocumentRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The handoff inbox, conversation insights and knowledge base health.
 *
 * <p>The tenancy cases matter most: the inbox is organization-wide, so a query
 * missing its organization predicate would show one customer's escalations to
 * another, and assignment must refuse anyone outside the organization.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandoffAndIntelligenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BotRepository botRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationNoteRepository noteRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Bot deletion and uploads would call the AI service; nothing here should.
    @MockitoBean private AiServiceClient aiServiceClient;

    private String tokenA;
    private String tokenB;
    private UUID userA;
    private UUID teammateA;
    private UUID userB;
    private Bot botA;
    private UUID botB;

    @BeforeEach
    void setUp() {
        noteRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        documentRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));

        User a = member(orgA, "a@example.com", "Ada Lovelace", MemberRole.OWNER);
        User teammate = member(orgA, "t@example.com", "Tim Berners-Lee", MemberRole.MEMBER);
        User b = member(orgB, "b@example.com", "Grace Hopper", MemberRole.OWNER);
        userA = a.getId();
        teammateA = teammate.getId();
        userB = b.getId();
        tokenA = jwtService.issue(userA, orgA.getId()).token();
        tokenB = jwtService.issue(userB, orgB.getId()).token();

        botA = botRepository.save(new Bot(orgA, "Support", "d", "en"));
        botB = botRepository.save(new Bot(orgB, "Other", "d", "en")).getId();
    }

    private User member(Organization organization, String email, String name, MemberRole role) {
        User user = userRepository.save(new User(name, email, passwordEncoder.encode("x")));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return user;
    }

    private UUID conversation(String visitor) {
        return conversationRepository.save(new Conversation(botA, visitor)).getId();
    }

    private void setResolution(UUID conversationId, String token, String resolution)
            throws Exception {
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/resolution", conversationId)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"resolution\": \"%s\"}".formatted(resolution)))
                .andExpect(status().isOk());
    }

    private void assign(UUID conversationId, String token, UUID assignee, int expected)
            throws Exception {
        String body =
                assignee == null
                        ? "{\"assigneeId\": null}"
                        : "{\"assigneeId\": \"%s\"}".formatted(assignee);
        mockMvc.perform(
                        patch("/api/v1/conversations/{id}/assignee", conversationId)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().is(expected));
    }

    // -------------------------------------------------------------- handoff --

    @Test
    void anEscalatedConversationLandsInTheSharedQueue() throws Exception {
        UUID id = conversation("v1");
        setResolution(id, tokenA, "ESCALATED");

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=UNASSIGNED")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].botName").value("Support"))
                .andExpect(jsonPath("$.items[0].escalatedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].assigneeId").value(nullValue()));
    }

    @Test
    void assigningMovesItFromTheQueueToTheAssignee() throws Exception {
        UUID id = conversation("v1");
        setResolution(id, tokenA, "ESCALATED");
        assign(id, tokenA, userA, 200);

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=MINE")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].assigneeName").value("Ada Lovelace"));

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=UNASSIGNED")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/v1/handoff/summary").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.mine").value(1))
                .andExpect(jsonPath("$.unassigned").value(0));
    }

    @Test
    void assigningAnOpenConversationEscalatesIt() throws Exception {
        UUID id = conversation("v1");
        assign(id, tokenA, teammateA, 200);

        Conversation stored = conversationRepository.findById(id).orElseThrow();
        assertThat(stored.getResolution()).isEqualTo(ConversationResolution.ESCALATED);
        assertThat(stored.getEscalatedAt()).isNotNull();
    }

    @Test
    void anAssigneeMustBelongToTheOrganization() throws Exception {
        UUID id = conversation("v1");
        assign(id, tokenA, userB, 400);
        assertThat(conversationRepository.findById(id).orElseThrow().getResolution())
                .isEqualTo(ConversationResolution.UNRESOLVED);
    }

    @Test
    void anotherOrganizationCanNeitherSeeNorAssign() throws Exception {
        UUID id = conversation("v1");
        setResolution(id, tokenA, "ESCALATED");

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=OPEN")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.totalItems").value(0));
        assign(id, tokenB, userB, 404);
    }

    @Test
    void resolvingMovesItToClosedAndCountsTowardResolutionTime() throws Exception {
        UUID id = conversation("v1");
        setResolution(id, tokenA, "ESCALATED");
        setResolution(id, tokenA, "RESOLVED");

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=CLOSED")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].resolvedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/handoff/summary").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.open").value(0))
                .andExpect(jsonPath("$.resolvedLast30Days").value(1))
                .andExpect(jsonPath("$.medianResolutionMinutes").value(0));
    }

    @Test
    void unassigningReturnsItToTheQueue() throws Exception {
        UUID id = conversation("v1");
        assign(id, tokenA, userA, 200);
        assign(id, tokenA, null, 200);

        mockMvc.perform(
                        get("/api/v1/handoff/conversations?view=UNASSIGNED")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    // ------------------------------------------------------------- insights --

    private void exchange(Conversation conversation, String question, boolean grounded) {
        messageRepository.save(Message.user(conversation, question));
        messageRepository.save(
                Message.assistant(
                        conversation,
                        grounded ? "Yes." : "Sorry, I don't know.",
                        List.of(),
                        grounded,
                        "fake",
                        10,
                        100));
    }

    @Test
    void insightsGroupQuestionsAndSurfaceTheUnanswered() throws Exception {
        Conversation one = conversationRepository.save(new Conversation(botA, "v1"));
        Conversation two = conversationRepository.save(new Conversation(botA, "v2"));
        exchange(one, "Do you ship to India?", true);
        exchange(two, "do you ship to india", true);
        exchange(two, "What is your refund window?", false);

        setResolution(one.getId(), tokenA, "RESOLVED");
        setResolution(two.getId(), tokenA, "ESCALATED");

        mockMvc.perform(
                        get("/api/v1/bots/{botId}/insights", botA.getId())
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuestions").value(3))
                .andExpect(jsonPath("$.unansweredQuestions").value(1))
                .andExpect(jsonPath("$.topQuestions[0].count").value(2))
                .andExpect(jsonPath("$.unansweredTopics[0].question")
                        .value("What is your refund window?"))
                .andExpect(jsonPath("$.totalConversations").value(2))
                .andExpect(jsonPath("$.satisfactionRate").value(0.5))
                .andExpect(jsonPath("$.trend.length()").value(30));
    }

    @Test
    void anotherOrganizationsBotHasNoInsights() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/{botId}/insights", botB)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------- health --

    private Document document(KnowledgeBase base, String name, long size, DocumentStatus status) {
        Document document = new Document(base, name, name, "application/pdf", size);
        if (status == DocumentStatus.FAILED) {
            document.markFailed("Could not extract text");
        } else {
            document.markAccepted(UUID.randomUUID(), status);
        }
        return documentRepository.save(document);
    }

    @Test
    void anEmptyKnowledgeBaseScoresZero() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/{botId}/knowledge/health", botA.getId())
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.issues[0].type").value("EMPTY_KNOWLEDGE_BASE"));
    }

    @Test
    void healthFindsVersionsDuplicatesFailuresAndGaps() throws Exception {
        KnowledgeBase base =
                knowledgeBaseRepository.save(
                        new KnowledgeBase(botA, "kb", UUID.randomUUID(), "ref"));
        document(base, "refund-policy-v1.pdf", 1_000, DocumentStatus.INDEXED);
        document(base, "refund-policy-v2.pdf", 2_000, DocumentStatus.INDEXED);
        document(base, "handbook.pdf", 5_555, DocumentStatus.INDEXED);
        document(base, "handbook (1).pdf", 5_555, DocumentStatus.INDEXED);
        document(base, "pricing-2019.pdf", 3_000, DocumentStatus.INDEXED);
        document(base, "broken.pdf", 10, DocumentStatus.FAILED);

        Document old = document(base, "faq.pdf", 4_000, DocumentStatus.INDEXED);
        jdbcTemplate.update(
                "UPDATE documents SET created_at = now() - interval '400 days' WHERE id = ?",
                old.getId());

        Conversation chat = conversationRepository.save(new Conversation(botA, "v1"));
        exchange(chat, "Can I pay with crypto?", false);

        mockMvc.perform(
                        get("/api/v1/bots/{botId}/knowledge/health", botA.getId())
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(7))
                .andExpect(jsonPath("$.issues[*].type").value(hasItem("CONFLICTING_VERSIONS")))
                .andExpect(jsonPath("$.issues[*].type").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.issues[*].type").value(hasItem("FAILED")))
                .andExpect(jsonPath("$.issues[*].type").value(hasItem("OUTDATED")))
                .andExpect(jsonPath("$.issues[*].title").value(hasItem("Refers to 2019")))
                .andExpect(jsonPath("$.suggestedFaqs[0].question").value("Can I pay with crypto?"))
                // High-severity findings come first.
                .andExpect(jsonPath("$.issues[0].severity").value("HIGH"));
    }

    @Test
    void anotherOrganizationsKnowledgeBaseIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/{botId}/knowledge/health", botB)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}

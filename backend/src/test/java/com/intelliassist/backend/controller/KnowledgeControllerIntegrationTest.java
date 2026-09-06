package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.DocumentStatus;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.AiServiceException;
import com.intelliassist.backend.integration.ai.dto.AiDocumentAcceptedResponse;
import com.intelliassist.backend.integration.ai.dto.AiDocumentResponse;
import com.intelliassist.backend.integration.ai.dto.AiKnowledgeBaseResponse;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.DocumentRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.JwtService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Upload, status and delete against a real database, with the AI service
 * stubbed.
 *
 * <p>The AI client is mocked here on purpose: {@code AiServiceClientTest}
 * already exercises the real HTTP stack against a socket, so repeating that
 * would be slow duplication. What this test covers is everything on our side of
 * the boundary — ownership, persistence, status mirroring and error mapping.
 */
@AutoConfigureMockMvc
class KnowledgeControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockitoBean private AiServiceClient aiServiceClient;

    private String tokenA;
    private String tokenB;
    private String memberTokenA;
    private UUID botA;
    private UUID botB;

    private final UUID aiKnowledgeBaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
        botRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization orgA = organizationRepository.save(new Organization("Org A", "org-a"));
        Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));

        tokenA = tokenFor(orgA, "owner-a@example.com", MemberRole.OWNER);
        tokenB = tokenFor(orgB, "owner-b@example.com", MemberRole.OWNER);
        memberTokenA = tokenFor(orgA, "member-a@example.com", MemberRole.MEMBER);

        botA = botRepository.save(new Bot(orgA, "Bot A", "desc", "en")).getId();
        botB = botRepository.save(new Bot(orgB, "Bot B", "desc", "en")).getId();

        Mockito.when(aiServiceClient.createKnowledgeBase(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiKnowledgeBaseResponse(
                                aiKnowledgeBaseId,
                                "bot-x",
                                "KB",
                                "active",
                                "gemini-embedding-001",
                                768,
                                Instant.now()));
    }

    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("Test", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    private void stubUploadAccepted(UUID aiDocumentId, String status, boolean duplicate) {
        Mockito.when(
                        aiServiceClient.uploadDocument(
                                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(
                        new AiDocumentAcceptedResponse(
                                aiDocumentId,
                                aiKnowledgeBaseId,
                                "handbook.pdf",
                                status,
                                duplicate,
                                "/api/v1/knowledge/documents/" + aiDocumentId));
    }

    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile(
                "file", name, "application/pdf", "%PDF-1.4 fake content".getBytes());
    }

    private UUID upload(String token, UUID botId, String filename) throws Exception {
        String body =
                mockMvc.perform(
                                multipart("/api/v1/bots/" + botId + "/documents")
                                        .file(pdf(filename))
                                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    // ------------------------------------------------------ knowledge base --

    @Test
    void creatingAKnowledgeBaseCallsTheAiServiceAndStoresTheReference() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/knowledge")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.botId").value(botA.toString()))
                .andExpect(jsonPath("$.documentCount").value(0));

        var stored = knowledgeBaseRepository.findAll().get(0);
        assertThat(stored.getAiKnowledgeBaseId()).isEqualTo(aiKnowledgeBaseId);
        assertThat(stored.getExternalRef()).isEqualTo("bot-" + botA);
    }

    @Test
    void internalAiIdentifiersAreNeverExposed() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/bots/" + botA + "/knowledge")
                                        .header("Authorization", "Bearer " + tokenA)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // The browser must never learn how to address the AI service.
        assertThat(body).doesNotContain(aiKnowledgeBaseId.toString());
    }

    @Test
    void creatingTwiceIsIdempotent() throws Exception {
        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/knowledge")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/bots/" + botA + "/knowledge")
                                .header("Authorization", "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated());

        assertThat(knowledgeBaseRepository.count()).isEqualTo(1);
        // The AI service is called once, not twice.
        Mockito.verify(aiServiceClient, Mockito.times(1))
                .createKnowledgeBase(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    // ------------------------------------------------------------- uploads --

    @Test
    void uploadReturns202AndRecordsTheAiDocumentId() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "pending", false);

        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        var stored = documentRepository.findById(documentId).orElseThrow();
        assertThat(stored.getAiDocumentId()).isEqualTo(aiDocumentId);
        assertThat(stored.getStatus()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    void uploadAutoCreatesTheKnowledgeBaseOnFirstUse() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "pending", false);

        upload(tokenA, botA, "handbook.pdf");

        // A client should not have to make two calls in the right order.
        assertThat(knowledgeBaseRepository.count()).isEqualTo(1);
    }

    @Test
    void aDuplicateUploadIsTreatedAsSuccess() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "indexed", true);

        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(pdf("handbook.pdf"))
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("INDEXED"));
    }

    @Test
    void unsupportedFileTypeIsRejectedBeforeReachingTheAiService() throws Exception {
        var image = new MockMultipartFile("file", "photo.png", "image/png", "fake".getBytes());

        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(image)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnsupportedMediaType());

        // Rejected locally, so no bandwidth or round trip was spent.
        Mockito.verify(aiServiceClient, Mockito.never())
                .uploadDocument(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        var empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(empty)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAiFailureLeavesNoOrphanDocumentRow() throws Exception {
        Mockito.when(
                        aiServiceClient.uploadDocument(
                                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenThrow(AiServiceException.unavailable(null));

        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(pdf("handbook.pdf"))
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isServiceUnavailable());

        // A row pointing at nothing would appear in the dashboard, never
        // finish, and be impossible to diagnose.
        assertThat(documentRepository.count()).isZero();
    }

    // -------------------------------------------------------------- status --

    @Test
    void statusIsRefreshedFromTheAiServiceWhileInProgress() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "pending", false);
        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        Mockito.when(aiServiceClient.getDocument(aiDocumentId))
                .thenReturn(
                        new AiDocumentResponse(
                                aiDocumentId, aiKnowledgeBaseId, "handbook.pdf", "application/pdf",
                                "indexed", 100L, 12, 47, null, Instant.now(), Instant.now()));

        mockMvc.perform(
                        get("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.queryable").value(true))
                .andExpect(jsonPath("$.chunkCount").value(47))
                .andExpect(jsonPath("$.pageCount").value(12));
    }

    @Test
    void aTerminalStatusIsNotRefreshedAgain() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "indexed", false);
        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        mockMvc.perform(
                        get("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Already INDEXED: polling the AI service again would be a wasted call.
        Mockito.verify(aiServiceClient, Mockito.never()).getDocument(Mockito.any());
    }

    @Test
    void aFailedIngestionSurfacesItsReason() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "pending", false);
        UUID documentId = upload(tokenA, botA, "scan.pdf");

        Mockito.when(aiServiceClient.getDocument(aiDocumentId))
                .thenReturn(
                        new AiDocumentResponse(
                                aiDocumentId, aiKnowledgeBaseId, "scan.pdf", "application/pdf",
                                "failed", 100L, null, null,
                                "No extractable text found.", Instant.now(), null));

        mockMvc.perform(
                        get("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.queryable").value(false))
                .andExpect(jsonPath("$.errorMessage").value("No extractable text found."));
    }

    @Test
    void anUnreachableAiServiceStillReturnsTheLastKnownStatus() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "embedding", false);
        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        Mockito.when(aiServiceClient.getDocument(aiDocumentId))
                .thenThrow(AiServiceException.unavailable(null));

        // Better than an error page: ingestion is probably still running fine.
        mockMvc.perform(
                        get("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMBEDDING"));
    }

    // -------------------------------------------------------------- delete --

    @Test
    void deleteRemovesTheDocumentFromBothSides() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "indexed", false);
        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        mockMvc.perform(
                        delete("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        Mockito.verify(aiServiceClient).deleteDocument(aiDocumentId);
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void ourRowSurvivesWhenTheAiDeleteFails() throws Exception {
        UUID aiDocumentId = UUID.randomUUID();
        stubUploadAccepted(aiDocumentId, "indexed", false);
        UUID documentId = upload(tokenA, botA, "handbook.pdf");

        Mockito.doThrow(AiServiceException.unavailable(null))
                .when(aiServiceClient)
                .deleteDocument(aiDocumentId);

        mockMvc.perform(
                        delete("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isServiceUnavailable());

        // Deleting locally first would strand the corpus in the AI service with
        // nothing left to identify it.
        assertThat(documentRepository.existsById(documentId)).isTrue();
    }

    // ----------------------------------------------------- TENANT ISOLATION --

    @Test
    void organizationBCannotUploadToOrganizationAsBot() throws Exception {
        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(pdf("handbook.pdf"))
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationBCannotReadOrganizationAsDocument() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "indexed", false);
        UUID documentId = upload(tokenA, botA, "secret.pdf");

        mockMvc.perform(
                        get("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationBCannotDeleteOrganizationAsDocument() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "indexed", false);
        UUID documentId = upload(tokenA, botA, "secret.pdf");

        mockMvc.perform(
                        delete("/api/v1/documents/" + documentId)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        assertThat(documentRepository.existsById(documentId)).isTrue();
    }

    @Test
    void documentListsNeverCrossOrganizations() throws Exception {
        stubUploadAccepted(UUID.randomUUID(), "indexed", false);
        upload(tokenA, botA, "a.pdf");

        mockMvc.perform(
                        get("/api/v1/bots/" + botB + "/documents")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    // --------------------------------------------------------------- roles --

    @Test
    void memberCannotUploadDocuments() throws Exception {
        mockMvc.perform(
                        multipart("/api/v1/bots/" + botA + "/documents")
                                .file(pdf("handbook.pdf"))
                                .header("Authorization", "Bearer " + memberTokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCanListDocuments() throws Exception {
        mockMvc.perform(
                        get("/api/v1/bots/" + botA + "/documents")
                                .header("Authorization", "Bearer " + memberTokenA))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedUploadIs401() throws Exception {
        mockMvc.perform(multipart("/api/v1/bots/" + botA + "/documents").file(pdf("a.pdf")))
                .andExpect(status().isUnauthorized());
    }
}

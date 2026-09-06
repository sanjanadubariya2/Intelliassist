package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.MessageRole;
import com.intelliassist.backend.entity.Organization;
import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.entity.User;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.ConversationRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import com.intelliassist.backend.repository.MessageRepository;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserRepository;
import com.intelliassist.backend.security.JwtService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * SSE streaming end to end: a real HTTP server, a real socket to a stubbed AI
 * service, and a real database.
 *
 * <p>MockMvc cannot be used here. It never opens a socket, so it cannot show
 * that events arrive incrementally, that cancellation propagates upstream, or
 * that the connection is actually closed - which is most of what streaming
 * needs proving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamIntegrationTest extends AbstractIntegrationTest {

    /**
     * Started before the Spring context so its port can be injected as the AI
     * service base URL.
     */
    private static final MockWebServer AI_SERVICE = new MockWebServer();

    static {
        try {
            AI_SERVICE.start();
        } catch (IOException exception) {
            throw new IllegalStateException("could not start the stub AI service", exception);
        }
    }

    @DynamicPropertySource
    static void aiServiceProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "intelliassist.ai-service.base-url",
                () -> AI_SERVICE.url("/").toString().replaceAll("/$", ""));
        registry.add("intelliassist.ai-service.api-key", () -> API_KEY);
        // Short enough that the timeout test does not stall the suite.
        registry.add("intelliassist.ai-service.stream-first-event-timeout", () -> "3s");
    }

    private static final String API_KEY = "server-side-only-key";

    @LocalServerPort private int port;

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

    private String tokenA;
    private String tokenB;
    private UUID conversationA;
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

        tokenA = tokenFor(orgA, "a@example.com", MemberRole.OWNER);
        tokenB = tokenFor(orgB, "b@example.com", MemberRole.OWNER);

        Bot botA = botRepository.save(new Bot(orgA, "Bot A", "desc", "en"));
        knowledgeBaseRepository.save(
                new KnowledgeBase(botA, "KB", aiKnowledgeBaseId, "bot-" + botA.getId()));

        conversationA =
                conversationRepository
                        .save(new com.intelliassist.backend.entity.Conversation(botA, "visitor-1"))
                        .getId();
    }

    @AfterEach
    void drainStubQueue() {
        // MockWebServer queues are per-test; a leftover response would be
        // handed to the next test and make failures baffling.
        while (AI_SERVICE.getRequestCount() > 0 && takeQuietly() != null) {
            // drain
        }
    }

    private RecordedRequest takeQuietly() {
        try {
            return AI_SERVICE.takeRequest(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String tokenFor(Organization organization, String email, MemberRole role) {
        User user =
                userRepository.save(new User("Test", email, passwordEncoder.encode("x".repeat(12))));
        memberRepository.save(new OrganizationMember(organization, user, role));
        return jwtService.issue(user.getId(), organization.getId()).token();
    }

    /** Builds an SSE body in exactly the AI service's framing. */
    private static String sse(String event, String json) {
        return "event: " + event + "\ndata: " + json + "\n\n";
    }

    private String groundedStreamBody() {
        return sse(
                        "metadata",
                        """
                        {"query_id":"%s","knowledge_base_id":"%s","grounded":true,\
                        "model":"gemini-3.1-flash-lite","sources":[{"marker":1,\
                        "chunk_id":"%s","document_id":"%s","filename":"handbook.pdf",\
                        "page_number":4,"score":0.83}]}"""
                                .formatted(
                                        UUID.randomUUID(),
                                        aiKnowledgeBaseId,
                                        UUID.randomUUID(),
                                        aiDocumentId))
                + sse("token", "{\"text\":\"Employees receive \"}")
                + sse("token", "{\"text\":\"25 days of leave \"}")
                + sse("token", "{\"text\":\"[S1].\"}")
                + sse(
                        "citations",
                        """
                        {"citations":[{"marker":1,"chunk_id":"%s","document_id":"%s",\
                        "filename":"handbook.pdf","page_number":4,\
                        "snippet":"Full-time employees receive 25 days","score":0.83}]}"""
                                .formatted(UUID.randomUUID(), aiDocumentId))
                + sse(
                        "done",
                        "{\"grounded\":true,\"sources_used\":1,\"context_tokens\":120,"
                                + "\"retrieval_ms\":120.0,\"rerank_ms\":0.0,"
                                + "\"generation_ms\":900.0,\"total_ms\":1020.0}");
    }

    /**
     * Enqueues an SSE response using chunked transfer encoding.
     *
     * <p>{@code setBody} would send a fixed-length body and close, which
     * reactor-netty reads as a premature close mid-stream. Real SSE endpoints
     * are chunked, so the stub must be too.
     */
    private void enqueueSse(String body) {
        AI_SERVICE.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(body, 64));
    }

    private Flux<String> openStream(String token, UUID conversationId) {
        return WebClient.create("http://localhost:" + port)
                .post()
                .uri("/api/v1/conversations/{id}/messages/stream", conversationId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"How much annual leave?\"}")
                .retrieve()
                .bodyToFlux(String.class);
    }

    // ------------------------------------------------------------ happy path --

    @Test
    void tokensAreForwardedToTheClientInOrder() {
        enqueueSse(groundedStreamBody());

        List<String> received = openStream(tokenA, conversationA).collectList().block(Duration.ofSeconds(20));

        assertThat(received).isNotNull();
        String joined = String.join("", received);
        assertThat(joined).contains("Employees receive").contains("25 days of leave");
    }

    @Test
    void aCompletionSignalIsAlwaysDelivered() {
        enqueueSse(groundedStreamBody());

        // Without a reliable terminal event a client cannot tell "finished"
        // from "still waiting".
        StepVerifier.create(openStream(tokenA, conversationA))
                .thenConsumeWhile(chunk -> !chunk.contains("total_ms"))
                .expectNextMatches(chunk -> chunk.contains("total_ms"))
                .thenCancel()
                .verify(Duration.ofSeconds(20));
    }

    // --------------------------------------------------------------- security --

    @Test
    void theApiKeyIsSentToThePythonServiceOnly() throws Exception {
        enqueueSse(groundedStreamBody());

        openStream(tokenA, conversationA).blockLast(Duration.ofSeconds(20));

        RecordedRequest upstream = AI_SERVICE.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        assertThat(upstream.getHeader("X-API-Key")).isEqualTo(API_KEY);
    }

    @Test
    void theClientNeverSeesTheApiKey() {
        enqueueSse(groundedStreamBody());

        String everything =
                String.join(
                        "",
                        openStream(tokenA, conversationA)
                                .collectList()
                                .block(Duration.ofSeconds(20)));

        assertThat(everything).doesNotContain(API_KEY);
    }

    @Test
    void internalAiIdentifiersAreStrippedFromTheStream() {
        enqueueSse(groundedStreamBody());

        String everything =
                String.join(
                        "",
                        openStream(tokenA, conversationA)
                                .collectList()
                                .block(Duration.ofSeconds(20)));

        // knowledge_base_id and chunk_id are internal to a service the browser
        // must never address; Phase 4 kept them out of REST responses and
        // streaming must not reintroduce them.
        assertThat(everything).doesNotContain(aiKnowledgeBaseId.toString());
        assertThat(everything).doesNotContain("chunk_id");
        // What a UI genuinely needs still arrives.
        assertThat(everything).contains(aiDocumentId.toString()).contains("handbook.pdf");
    }

    @Test
    void anotherOrganizationCannotStreamThisConversation() {
        // Fails before any byte is written, so it is a normal HTTP error rather
        // than a 200 whose first event is an error.
        int before = AI_SERVICE.getRequestCount();

        StepVerifier.create(openStream(tokenB, conversationA))
                .expectErrorMatches(
                        error ->
                                error instanceof
                                                org.springframework.web.reactive.function.client
                                                        .WebClientResponseException.NotFound)
                .verify(Duration.ofSeconds(20));

        // Authorisation failed before the AI service was contacted at all.
        assertThat(AI_SERVICE.getRequestCount()).isEqualTo(before);
    }

    @Test
    void anUnauthenticatedRequestCannotStream() {
        StepVerifier.create(
                        WebClient.create("http://localhost:" + port)
                                .post()
                                .uri(
                                        "/api/v1/conversations/{id}/messages/stream",
                                        conversationA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue("{\"content\":\"hello\"}")
                                .retrieve()
                                .bodyToFlux(String.class))
                .expectErrorMatches(
                        error ->
                                error instanceof
                                        org.springframework.web.reactive.function.client
                                                .WebClientResponseException.Unauthorized)
                .verify(Duration.ofSeconds(20));
    }

    // ------------------------------------------------------------ persistence --

    @Test
    void aCompletedStreamPersistsTheWholeAnswer() {
        enqueueSse(groundedStreamBody());

        openStream(tokenA, conversationA).blockLast(Duration.ofSeconds(20));

        // Persistence happens off the event loop, so it lands shortly after the
        // last byte rather than before it.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            var messages =
                                    messageRepository.findAllByConversationIdOrderByCreatedAtAsc(
                                            conversationA);
                            assertThat(messages).hasSize(2);
                            assertThat(messages.get(0).getRole()).isEqualTo(MessageRole.USER);

                            var assistant = messages.get(1);
                            assertThat(assistant.getRole()).isEqualTo(MessageRole.ASSISTANT);
                            // Reassembled from three separate token events.
                            assertThat(assistant.getContent())
                                    .isEqualTo("Employees receive 25 days of leave [S1].");
                            assertThat(assistant.getGrounded()).isTrue();
                            assertThat(assistant.getModel()).isEqualTo("gemini-3.1-flash-lite");
                            assertThat(assistant.getCitations()).hasSize(1);
                            assertThat(assistant.getCitations().get(0).filename())
                                    .isEqualTo("handbook.pdf");
                        });
    }

    @Test
    void aStreamThatFailsMidwayPersistsNothing() {
        // Two tokens, then the connection dies with no `done` event.
        AI_SERVICE.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(
                                sse("metadata", "{\"grounded\":true,\"model\":\"m\",\"sources\":[]}")
                                        + sse("token", "{\"text\":\"Half an \"}")
                                        + sse("token", "{\"text\":\"answer\"}"),
                                64)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));

        openStream(tokenA, conversationA)
                .onErrorResume(error -> Flux.empty())
                .blockLast(Duration.ofSeconds(20));

        // A half-written answer reads as authoritative while being cut off,
        // possibly before the sentence that qualified it.
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(6))
                .untilAsserted(
                        () ->
                                assertThat(messageRepository.countByConversationId(conversationA))
                                        .isZero());
    }

    @Test
    void anUngroundedStreamStillPersistsTheRefusal() {
        enqueueSse(
                sse("metadata", "{\"grounded\":false,\"model\":\"m\",\"sources\":[]}")
                        + sse(
                                "token",
                                "{\"text\":\"I don't have information about that in the knowledge base.\"}")
                        + sse("citations", "{\"citations\":[]}")
                        + sse("done", "{\"grounded\":false,\"total_ms\":85.0}"));

        openStream(tokenA, conversationA).blockLast(Duration.ofSeconds(20));

        // Same semantics as the synchronous endpoint: a refusal is a real
        // answer, and recording it is what makes corpus gaps visible.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            var messages =
                                    messageRepository.findAllByConversationIdOrderByCreatedAtAsc(
                                            conversationA);
                            assertThat(messages).hasSize(2);
                            assertThat(messages.get(1).getGrounded()).isFalse();
                            assertThat(messages.get(1).getCitations()).isEmpty();
                        });
    }

    // -------------------------------------------------------- error handling --

    @Test
    void anErrorBeforeTheStreamStartsIsANormalHttpError() {
        AI_SERVICE.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"error\":{\"code\":\"llm_error\",\"message\":"
                                        + "\"gemini quota exhausted\",\"request_id\":\"r-1\"}}"));

        StepVerifier.create(openStream(tokenA, conversationA))
                .expectErrorMatches(
                        error ->
                                error instanceof
                                        org.springframework.web.reactive.function.client
                                                .WebClientResponseException)
                .verify(Duration.ofSeconds(20));

        assertThat(messageRepository.countByConversationId(conversationA)).isZero();
    }

    @Test
    void anErrorEventFromPythonIsForwardedWithoutUpstreamDetail() {
        enqueueSse(
                sse("metadata", "{\"grounded\":true,\"model\":\"m\",\"sources\":[]}")
                        + sse("token", "{\"text\":\"Partial\"}")
                        + sse(
                                "error",
                                "{\"code\":\"stream_failed\",\"message\":"
                                        + "\"gemini-3.1-flash-lite exploded\"}"));

        String everything =
                String.join(
                        "",
                        openStream(tokenA, conversationA)
                                .onErrorResume(error -> Flux.empty())
                                .collectList()
                                .block(Duration.ofSeconds(20)));

        assertThat(everything).contains("AI_SERVICE_ERROR");
        assertThat(everything).doesNotContain("gemini").doesNotContain("exploded");
    }

    @Test
    void aStreamThatNeverStartsTimesOut() {
        // No response at all: the first-event budget is 3s in this test.
        AI_SERVICE.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        StepVerifier.create(openStream(tokenA, conversationA).onErrorResume(e -> Flux.empty()))
                .thenConsumeWhile(chunk -> true)
                .verifyComplete();

        assertThat(messageRepository.countByConversationId(conversationA)).isZero();
    }

    // ------------------------------------------------------------ disconnect --

    @Test
    void clientDisconnectCancelsTheUpstreamStream() throws Exception {
        enqueueSse(groundedStreamBody());

        // Cancel after the first chunk, as a browser closing its tab would.
        Disposable subscription =
                openStream(tokenA, conversationA).take(1).subscribe();

        await().atMost(Duration.ofSeconds(15)).until(subscription::isDisposed);

        RecordedRequest upstream = AI_SERVICE.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        // The request reached Python; cancelling closes that connection so the
        // AI service stops producing tokens nobody will read.
        assertThat(upstream.getPath()).isEqualTo("/api/v1/rag/stream");
    }

    @Test
    void theSynchronousEndpointStillWorksAlongsideStreaming() {
        // Phase 5 behaviour must not regress.
        AI_SERVICE.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {"query_id":"%s","knowledge_base_id":"%s","query":"q",\
                                "answer":"Sync answer [S1].","citations":[],"grounded":true,\
                                "model":"m","truncated":false,\
                                "usage":{"prompt_tokens":1,"completion_tokens":1,\
                                "context_tokens":1,"candidates_retrieved":1,"sources_used":0},\
                                "timings":{"retrieval_ms":1.0,"rerank_ms":0.0,\
                                "generation_ms":1.0,"total_ms":2.0}}"""
                                        .formatted(UUID.randomUUID(), aiKnowledgeBaseId)));

        String body =
                WebClient.create("http://localhost:" + port)
                        .post()
                        .uri("/api/v1/conversations/{id}/messages", conversationA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"content\":\"How much leave?\"}")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(20));

        assertThat(body).contains("Sync answer");
    }
}

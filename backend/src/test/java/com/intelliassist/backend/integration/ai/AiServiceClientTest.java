package com.intelliassist.backend.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelliassist.backend.config.AiServiceProperties;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The AI client, tested over a real socket.
 *
 * <p>A stubbed {@code WebClient} would only prove our stubs behave; this runs
 * the actual HTTP stack, so headers, timeouts, multipart encoding and error
 * translation are all genuinely exercised.
 */
class AiServiceClientTest {

    private MockWebServer server;
    private AiServiceClient client;

    private static final String API_KEY = "test-api-key";
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();

        AiServiceProperties properties =
                new AiServiceProperties(
                        server.url("/").toString().replaceAll("/$", ""),
                        API_KEY,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        6);

        client = new AiServiceClient(new AiWebClientConfig().aiWebClient(properties), properties);
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private void enqueueJson(int status, String body) {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(status)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body));
    }

    private String errorBody(String code, String message) {
        return """
                {"error":{"code":"%s","message":"%s","details":{},"request_id":"upstream-123"}}"""
                .formatted(code, message);
    }

    // ------------------------------------------------------------- headers --

    @Test
    void everyRequestCarriesTheApiKey() throws Exception {
        enqueueJson(200, """
                {"id":"%s","status":"indexed","chunk_count":3}""".formatted(documentId));

        client.getDocument(documentId);

        RecordedRequest request = server.takeRequest();
        // Attached by a WebClient filter, so no call site can forget it.
        assertThat(request.getHeader("X-API-Key")).isEqualTo(API_KEY);
    }

    @Test
    void snakeCaseResponseFieldsBindCorrectly() {
        enqueueJson(
                200,
                """
                {"id":"%s","knowledge_base_id":"%s","filename":"a.pdf","mime_type":"application/pdf",
                 "status":"indexed","byte_size":1234,"page_count":7,"chunk_count":42,
                 "error_message":null}"""
                        .formatted(documentId, knowledgeBaseId));

        var response = client.getDocument(documentId);

        assertThat(response.knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(response.chunkCount()).isEqualTo(42);
        assertThat(response.pageCount()).isEqualTo(7);
        assertThat(response.byteSize()).isEqualTo(1234L);
    }

    @Test
    void unknownResponseFieldsDoNotBreakBinding() {
        // The AI service may add fields; a strict binding would turn a harmless
        // addition into an outage on our side.
        enqueueJson(
                200,
                """
                {"id":"%s","status":"indexed","a_brand_new_field":"surprise"}"""
                        .formatted(documentId));

        assertThat(client.getDocument(documentId).id()).isEqualTo(documentId);
    }

    // ----------------------------------------------------------- knowledge --

    @Test
    void createKnowledgeBaseSendsExternalRefAsSnakeCase() throws Exception {
        enqueueJson(
                201,
                """
                {"id":"%s","external_ref":"bot-1","name":"KB","status":"active",
                 "embedding_model":"gemini-embedding-001","embedding_dim":768}"""
                        .formatted(knowledgeBaseId));

        var created = client.createKnowledgeBase("bot-1", "KB", "desc");

        assertThat(created.id()).isEqualTo(knowledgeBaseId);
        assertThat(created.embeddingDim()).isEqualTo(768);

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"external_ref\":\"bot-1\"").doesNotContain("externalRef");
    }

    @Test
    void uploadSendsMultipartWithKnowledgeBaseIdAndFilename() throws Exception {
        enqueueJson(
                202,
                """
                {"id":"%s","knowledge_base_id":"%s","filename":"handbook.pdf",
                 "status":"pending","duplicate":false,"poll_url":"/x"}"""
                        .formatted(documentId, knowledgeBaseId));

        client.uploadDocument(knowledgeBaseId, "handbook.pdf", "application/pdf", "data".getBytes());

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();

        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        assertThat(body).contains("knowledge_base_id").contains(knowledgeBaseId.toString());
        // Without a filename the AI service cannot sniff the type and rejects
        // the upload as unsupported.
        assertThat(body).contains("handbook.pdf");
    }

    @Test
    void duplicateUploadIsReturnedAsSuccessNotAnError() {
        enqueueJson(
                202,
                """
                {"id":"%s","knowledge_base_id":"%s","filename":"a.pdf",
                 "status":"indexed","duplicate":true,"poll_url":"/x"}"""
                        .formatted(documentId, knowledgeBaseId));

        var accepted =
                client.uploadDocument(knowledgeBaseId, "a.pdf", "application/pdf", "x".getBytes());

        assertThat(accepted.duplicate()).isTrue();
        assertThat(accepted.id()).isEqualTo(documentId);
    }

    // ------------------------------------------------------ error mapping --

    @Test
    void upstream401BecomesOurInternalErrorNotTheCallers401() {
        // Their 401 means *our* API key is wrong. Reporting it as a 401 would
        // tell a dashboard user their own login failed.
        enqueueJson(401, errorBody("unauthorized", "Missing X-API-Key header."));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception -> {
                            assertThat(exception.getStatus())
                                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                            assertThat(exception.getCode()).isEqualTo("AI_SERVICE_MISCONFIGURED");
                        });
    }

    @Test
    void upstream404BecomesNotFound() {
        enqueueJson(404, errorBody("not_found", "Document does not exist"));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void upstream413BecomesPayloadTooLarge() {
        enqueueJson(413, errorBody("payload_too_large", "File exceeds the 50 MB limit."));

        assertThatThrownBy(
                        () ->
                                client.uploadDocument(
                                        knowledgeBaseId, "big.pdf", "application/pdf", "x".getBytes()))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception ->
                                assertThat(exception.getStatus())
                                        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void upstream415BecomesUnsupportedMediaType() {
        enqueueJson(415, errorBody("unsupported_document_type", "image/png is not supported"));

        assertThatThrownBy(
                        () ->
                                client.uploadDocument(
                                        knowledgeBaseId, "a.png", "application/pdf", "x".getBytes()))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception ->
                                assertThat(exception.getStatus())
                                        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void upstream422BecomesAnActionableRejection() {
        enqueueJson(422, errorBody("extraction_error", "No extractable text found."));

        assertThatThrownBy(
                        () ->
                                client.uploadDocument(
                                        knowledgeBaseId, "scan.pdf", "application/pdf", "x".getBytes()))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception -> {
                            assertThat(exception.getStatus())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            // The client is told what to do without being told
                            // how the pipeline works.
                            assertThat(exception.getMessage()).contains("scan with no text");
                        });
    }

    @Test
    void upstream502BecomesATransient503() {
        // Their 502 means Gemini failed after retries. Retrying later may work,
        // so the caller is told "unavailable", not "bad request".
        enqueueJson(502, errorBody("llm_error", "Gemini generation failed: 503 UNAVAILABLE"));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception ->
                                assertThat(exception.getStatus())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void upstreamMessagesNeverReachTheClient() {
        // The upstream message names the provider and the model. Our client
        // gets wording we chose; the detail goes to the log.
        enqueueJson(502, errorBody("llm_error", "gemini-3.1-flash-lite quota exhausted"));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception -> {
                            assertThat(exception.getMessage())
                                    .doesNotContain("gemini")
                                    .doesNotContain("quota");
                            // ...but it is retained for correlation in logs.
                            assertThat(exception.getUpstreamRequestId()).isEqualTo("upstream-123");
                        });
    }

    @Test
    void errorResponseWithNoBodyIsStillMapped() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOf(AiServiceException.class);
    }

    // ---------------------------------------------------- timeouts, retries --

    @Test
    void aSlowResponseTimesOut() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{}")
                        .setBodyDelay(6, java.util.concurrent.TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void readsAreRetried() {
        // Two failures, then success: a read is idempotent, so retrying it
        // turns a transient blip into a served request.
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        enqueueJson(200, """
                {"id":"%s","status":"indexed"}""".formatted(documentId));

        assertThat(client.getDocument(documentId).id()).isEqualTo(documentId);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void uploadsAreNeverRetried() {
        // A retry could enqueue a second ingestion for a request that actually
        // succeeded, and the caller would have no way to know.
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(
                        () ->
                                client.uploadDocument(
                                        knowledgeBaseId, "a.pdf", "application/pdf", "x".getBytes()))
                .isInstanceOf(AiServiceException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void anUnreachableServiceIsReportedAsUnavailable() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.getDocument(documentId))
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception ->
                                assertThat(exception.getStatus())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void deletingAnAlreadyGoneDocumentSucceeds() {
        // Already absent is the desired end state, so a 404 on delete is not a
        // failure - it makes the operation genuinely idempotent.
        enqueueJson(404, errorBody("not_found", "Document does not exist"));

        client.deleteDocument(documentId);
    }

    @Test
    void healthCheckReportsFalseWhenTheServiceIsDown() throws IOException {
        server.shutdown();

        assertThat(client.isHealthy()).isFalse();
    }

    // ---------------------------------------------------------- streaming --

    /** Builds one SSE frame without embedding escapes in a text block. */
    private static String frame(String event, String json) {
        return "event: " + event + "\n" + "data: " + json + "\n\n";
    }

    private void enqueueSseStream(String body) {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(body, 32));
    }

    @Test
    void streamEventsArriveWithTheirNamesIntact() {
        enqueueSseStream(
                frame("metadata", "{\"grounded\":true}")
                        + frame("token", "{\"text\":\"hi\"}")
                        + frame("done", "{\"grounded\":true}"));

        var events = client.streamQuery(knowledgeBaseId, "q").collectList().block();

        assertThat(events).isNotNull();
        // The event name is the contract a client codes against; losing it
        // would make the stream unreadable.
        assertThat(events).extracting(AiStreamEvent::name)
                .containsExactly("metadata", "token", "done");
        assertThat(events.get(1).data()).contains("hi");
    }

    @Test
    void aStreamThatGoesSilentMidAnswerIsCutOffByTheIdleTimeout() {
        // One event arrives, then a long silence. The idle budget is 2s in this
        // test; the first-event budget is 3s. A single timeout() would have
        // applied the larger value to every gap, so this pins the distinction.
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(frame("token", "{\"text\":\"hi\"}"), 32)
                        .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        long startedAt = System.currentTimeMillis();

        assertThatThrownBy(() -> client.streamQuery(knowledgeBaseId, "q").collectList().block())
                .isInstanceOf(AiServiceException.class);

        // The idle budget is 2s and the server would not speak again for 5s.
        // Failing early is proof the idle timeout is wired, not just configured.
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(4_000);
    }

    @Test
    void aStreamRejectedBeforeItStartsIsTranslatedNotSwallowed() {
        enqueueJson(503, errorBody("llm_error", "gemini unavailable"));

        assertThatThrownBy(() -> client.streamQuery(knowledgeBaseId, "q").collectList().block())
                .isInstanceOfSatisfying(
                        AiServiceException.class,
                        exception -> {
                            assertThat(exception.getStatus())
                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(exception.getMessage()).doesNotContain("gemini");
                        });
    }

    @Test
    void healthCheckReportsTrueWhenTheServiceAnswers() {
        enqueueJson(200, """
                {"status":"ok"}""");

        assertThat(client.isHealthy()).isTrue();
    }
}

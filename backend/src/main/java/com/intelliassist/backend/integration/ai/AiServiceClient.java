package com.intelliassist.backend.integration.ai;

import java.util.List;
import com.intelliassist.backend.integration.ai.dto.AiConversationTurn;
import com.intelliassist.backend.config.AiServiceProperties;
import com.intelliassist.backend.integration.ai.dto.AiDocumentAcceptedResponse;
import com.intelliassist.backend.integration.ai.dto.AiDocumentResponse;
import com.intelliassist.backend.integration.ai.dto.AiErrorResponse;
import com.intelliassist.backend.integration.ai.dto.AiKnowledgeBaseRequest;
import com.intelliassist.backend.integration.ai.dto.AiKnowledgeBaseResponse;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryRequest;
import com.intelliassist.backend.integration.ai.dto.AiRagQueryResponse;
import com.intelliassist.backend.integration.ai.dto.AiStreamEvent;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The only code that talks to the Python AI service.
 *
 * <p>Everything crosses this boundary: no controller and no other service holds
 * a {@link WebClient}. That is what makes the API key impossible to leak by
 * accident and the retry policy a single decision rather than a habit.
 *
 * <p><strong>Retries.</strong> Reads are retried; writes are not. Upload,
 * knowledge-base creation and generation all have side effects or cost money,
 * and the AI service already retries Gemini internally with backoff — a second
 * layer here would multiply latency and spend for no gain. This is set out in
 * {@code backend/docs/AI_INTEGRATION_CONTRACT.md} section 5.
 *
 * <p>Calls are {@code block()}ed on purpose. This is a servlet application; the
 * caller is a request thread that has nothing else to do, and returning
 * {@code Mono} everywhere would make the service layer reactive for no benefit.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private static final int READ_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

    /** WebClient decodes SSE framing natively into this type. */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final AiServiceProperties properties;

    public AiServiceClient(WebClient aiWebClient, AiServiceProperties properties) {
        this.webClient = aiWebClient;
        this.properties = properties;
    }

    // ------------------------------------------------------ knowledge base --

    /**
     * Creates a knowledge base. Not retried: a duplicate {@code externalRef}
     * returns 409, so a blind retry would either duplicate work or confuse the
     * caller with a conflict it did not cause.
     */
    public AiKnowledgeBaseResponse createKnowledgeBase(
            String externalRef, String name, String description) {

        log.debug("creating AI knowledge base for external_ref={}", externalRef);

        return execute(
                webClient
                        .post()
                        .uri("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(AiKnowledgeBaseRequest.of(externalRef, name, description))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        .bodyToMono(AiKnowledgeBaseResponse.class),
                properties.readTimeout(),
                false);
    }

    /** Idempotent, so safe to retry. */
    public void deleteKnowledgeBase(UUID knowledgeBaseId) {
        log.debug("deleting AI knowledge base {}", knowledgeBaseId);

        execute(
                webClient
                        .delete()
                        .uri("/api/v1/knowledge-bases/{id}", knowledgeBaseId)
                        .retrieve()
                        // Already gone is the desired end state, so 404 is
                        // declared a non-error *before* the general handler.
                        // retrieve() also applies its own default 4xx handler
                        // last, so simply omitting 404 from the error predicate
                        // is not enough - it has to be claimed explicitly.
                        .onStatus(AiServiceClient::isNotFound, response -> Mono.empty())
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        // toBodilessEntity, not bodyToMono(Void): the body must
                        // be drained or the connection is never released back
                        // to the pool.
                        .toBodilessEntity()
                        .then(),
                properties.readTimeout(),
                true);
    }

    // ----------------------------------------------------------- documents --

    /**
     * Uploads a document for asynchronous ingestion.
     *
     * <p><strong>Never retried.</strong> The AI service hashes content, so a
     * repeat is not destructive — but an automatic retry on a timeout could
     * enqueue a second ingestion for a request that actually succeeded, and the
     * caller has no way to know. A person can retry; the code will not.
     */
    public AiDocumentAcceptedResponse uploadDocument(
            UUID knowledgeBaseId, String filename, String contentType, byte[] content) {

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("knowledge_base_id", knowledgeBaseId.toString());
        body.part(
                        "file",
                        new ByteArrayResource(content) {
                            @Override
                            public String getFilename() {
                                // Without a filename the AI service cannot sniff
                                // the type and rejects the upload as unsupported.
                                return filename;
                            }
                        })
                .contentType(MediaType.parseMediaType(contentType));

        log.info(
                "uploading document '{}' ({} bytes) to AI knowledge base {}",
                filename,
                content.length,
                knowledgeBaseId);

        return execute(
                webClient
                        .post()
                        .uri("/api/v1/knowledge/documents")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(body.build()))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        .bodyToMono(AiDocumentAcceptedResponse.class),
                // Uploads carry up to 50 MB, so they need longer than a read.
                properties.queryTimeout(),
                false);
    }

    /** Status polling. Read-only, so retried. */
    public AiDocumentResponse getDocument(UUID documentId) {
        return execute(
                webClient
                        .get()
                        .uri("/api/v1/knowledge/documents/{id}", documentId)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        .bodyToMono(AiDocumentResponse.class),
                properties.readTimeout(),
                true);
    }

    /** Idempotent, so safe to retry. */
    public void deleteDocument(UUID documentId) {
        execute(
                webClient
                        .delete()
                        .uri("/api/v1/knowledge/documents/{id}", documentId)
                        .retrieve()
                        .onStatus(AiServiceClient::isNotFound, response -> Mono.empty())
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        .toBodilessEntity()
                        .then(),
                properties.readTimeout(),
                true);
    }

    /**
     * Deleting something already absent is success, not failure.
     *
     * <p>Compared by numeric value: {@code HttpStatusCode} is an interface, so
     * an identity check against the {@code HttpStatus} enum is not reliable.
     */
    private static boolean isNotFound(HttpStatusCode status) {
        return status.value() == HttpStatus.NOT_FOUND.value();
    }

    // ----------------------------------------------------------------- rag --

    /**
     * Asks a grounded question against a knowledge base.
     *
     * <p><strong>Never retried.</strong> Generation costs a model call, and the
     * AI service already retries the provider internally with backoff and
     * jitter — it even distinguishes a transient rate limit from an exhausted
     * quota. A second retry layer here would multiply spend and latency for a
     * failure that has already been retried properly.
     *
     * <p>Uses the longer query timeout: retrieval, reranking and generation add
     * up, and the AI service's own model timeout is 60 seconds.
     */
    public AiRagQueryResponse query(UUID knowledgeBaseId, String question) {
        return query(knowledgeBaseId, question, null);
    }

    /**
     * The same query, with the conversation that preceded it.
     *
     * <p>The history is not stored by the AI service; it is used once, to
     * rewrite a follow-up into a standalone search query. Passing null or an
     * empty list gives exactly the single-question behaviour above.
     */
    public AiRagQueryResponse query(
            UUID knowledgeBaseId, String question, List<AiConversationTurn> history) {
        log.debug("querying AI knowledge base {}", knowledgeBaseId);

        return execute(
                webClient
                        .post()
                        .uri("/api/v1/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(AiRagQueryRequest.of(knowledgeBaseId, question, history))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                        .bodyToMono(AiRagQueryResponse.class),
                properties.queryTimeout(),
                false);
    }

    /**
     * Streams a grounded answer as Server-Sent Events.
     *
     * <p>Returns a cold {@link Flux}: nothing is sent until something
     * subscribes, and cancelling the subscription closes the upstream
     * connection. That is what makes a browser closing its tab stop generation
     * rather than leaving the AI service producing tokens nobody will read.
     *
     * <p>Never retried, for the same reason as {@link #query}: a retry would
     * re-run generation, and by then part of the answer may already have
     * reached the client.
     *
     * <p>Two timeouts, and neither caps the total duration - a long answer is
     * legitimately long. The first bounds how long we wait for the stream to
     * start, which covers retrieval and reranking; the second bounds silence
     * between events once it is flowing.
     */
    public Flux<AiStreamEvent> streamQuery(UUID knowledgeBaseId, String question) {
        return streamQuery(knowledgeBaseId, question, null);
    }

    /** The streamed query, with conversational context. */
    public Flux<AiStreamEvent> streamQuery(
            UUID knowledgeBaseId, String question, List<AiConversationTurn> history) {
        log.debug("opening AI stream for knowledge base {}", knowledgeBaseId);

        return webClient
                .post()
                .uri("/api/v1/rag/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(AiRagQueryRequest.of(knowledgeBaseId, question, history))
                .retrieve()
                // An error before the stream opens is an ordinary HTTP failure
                // and is translated exactly as a non-streaming call would be,
                // so the caller can still return a normal status code.
                .onStatus(HttpStatusCode::isError, AiServiceClient::translate)
                .bodyToFlux(SSE_TYPE)
                // Two distinct budgets. The single-argument timeout() applies
                // the same duration to every element, which would have left the
                // configured idle timeout unused and tolerated a 75s silence
                // mid-answer. This overload separates them: a long wait for the
                // stream to start (retrieval and reranking), then a much
                // shorter tolerance for silence once tokens are flowing.
                .timeout(
                        Mono.delay(properties.streamFirstEventTimeout()),
                        item -> Mono.delay(properties.streamIdleTimeout()))
                .map(event -> new AiStreamEvent(event.event(), event.data()))
                // A null event name would mean the AI service stopped framing
                // events the way its contract says; drop rather than guess.
                .filter(event -> event.name() != null)
                .onErrorMap(AiServiceClient::toAiException);
    }

    /**
     * Maps a streaming failure onto our own exception type.
     *
     * <p>{@link AiServiceException} passes through untouched so an HTTP status
     * decided before the stream opened is not flattened into "unavailable".
     */
    private static Throwable toAiException(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof AiServiceException aiException) {
            return aiException;
        }
        if (cause instanceof TimeoutException) {
            return AiServiceException.timeout();
        }
        return AiServiceException.unavailable(null);
    }

    /** Liveness of the AI service, for our own readiness reporting. */
    public boolean isHealthy() {
        try {
            webClient
                    .get()
                    .uri("/health/live")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            return true;
        } catch (Exception exception) {
            log.warn("AI service health check failed: {}", exception.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------ internals --

    /**
     * Runs a call with a timeout, optional retry, and uniform failure mapping.
     *
     * @param retryable whether the operation is safe to repeat; only ever true
     *                  for reads and idempotent deletes
     */
    private <T> T execute(Mono<T> call, Duration timeout, boolean retryable) {
        Mono<T> withTimeout = call.timeout(timeout);

        if (retryable) {
            withTimeout =
                    withTimeout.retryWhen(
                            reactor.util.retry.Retry.backoff(READ_RETRIES, RETRY_BACKOFF)
                                    // Only connection-level problems. A 4xx will
                                    // fail identically every time.
                                    .filter(
                                            throwable ->
                                                    throwable instanceof WebClientRequestException
                                                            || throwable instanceof TimeoutException));
        }

        try {
            return withTimeout.block();
        } catch (AiServiceException exception) {
            log.warn(
                    "AI service rejected the request: code={} upstream_request_id={}",
                    exception.getUpstreamCode(),
                    exception.getUpstreamRequestId());
            throw exception;
        } catch (Exception exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof AiServiceException aiException) {
                throw aiException;
            }
            if (cause instanceof TimeoutException) {
                log.error("AI service timed out after {}", timeout);
                throw AiServiceException.timeout();
            }
            log.error("AI service unreachable: {}", cause.getClass().getSimpleName());
            throw AiServiceException.unavailable(null);
        }
    }

    /**
     * Reactor wraps failures from {@code block()}; unwrap to the real cause so
     * an {@link AiServiceException} is not misreported as "unreachable".
     */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            if (current instanceof AiServiceException || current instanceof TimeoutException) {
                return current;
            }
            current = current.getCause();
        }
        return current;
    }

    /**
     * Maps an AI error response onto our own semantics.
     *
     * <p>The upstream message is read for the log and then dropped. It can name
     * models and providers, and none of that belongs in a response to a
     * dashboard user or a website visitor.
     */
    private static Mono<Throwable> translate(org.springframework.web.reactive.function.client.ClientResponse response) {
        HttpStatusCode status = response.statusCode();

        return response.bodyToMono(AiErrorResponse.class)
                .defaultIfEmpty(new AiErrorResponse(null))
                .map(
                        body -> {
                            String requestId = body.requestIdOrNull();
                            String code = body.codeOrUnknown();

                            log.warn(
                                    "AI service returned {}: code={} upstream_request_id={} message={}",
                                    status.value(),
                                    code,
                                    requestId,
                                    body.messageOrEmpty());

                            return (Throwable) mapStatus(status, code, requestId);
                        });
    }

    private static AiServiceException mapStatus(
            HttpStatusCode status, String code, String requestId) {

        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            // Our API key is wrong or missing: a deployment error, not the
            // caller's, so it must not surface as their 401.
            return AiServiceException.misconfigured(requestId);
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return AiServiceException.notFound(requestId);
        }
        if (status.value() == HttpStatus.CONFLICT.value()) {
            return AiServiceException.conflict("That resource already exists.", requestId);
        }
        if (status.value() == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            return AiServiceException.rejected(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "The file is too large.",
                    requestId);
        }
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return AiServiceException.rejected(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_DOCUMENT_TYPE",
                    "That file type is not supported.",
                    requestId);
        }
        if (status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return AiServiceException.rejected(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DOCUMENT_REJECTED",
                    "The document could not be processed. It may be empty, corrupt, or a scan with no text.",
                    requestId);
        }
        // 5xx: their problem, transient from our side.
        return AiServiceException.upstreamFailure(code, requestId);
    }
}
